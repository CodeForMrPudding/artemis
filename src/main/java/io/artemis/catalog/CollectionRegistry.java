package io.artemis.catalog;

import io.artemis.api.error.*;
import io.artemis.api.model.CollectionInfo;
import io.artemis.index.FlatIndex;
import io.artemis.storage.*;
import java.util.*;

import static io.artemis.storage.Namespace.*;

/** Catalog cache. Blocking collection leases are acquired outside the registry monitor. */
public final class CollectionRegistry implements AutoCloseable {
    private final Map<String, CollectionState> collections = new HashMap<>();
    private boolean closed;

    public synchronized CollectionState forWrite(String name) {
        requireOpen();
        var state = collections.get(name);
        if (state == null) throw new VectorDbException(ErrorCode.NOT_FOUND, "Collection not found: " + name);
        return state;
    }
    public synchronized boolean contains(String name) { requireOpen(); return collections.containsKey(name); }
    public synchronized void add(CollectionState state) {
        requireOpen();
        if (collections.putIfAbsent(state.config().name(), state) != null) throw new IllegalStateException("Duplicate collection");
    }
    public CollectionState.ReadLease read(String name) {
        CollectionState state;
        synchronized (this) { state = forWrite(name); }
        return state.read();
    }
    public CollectionState.ReadLease readIfPresent(String name) {
        CollectionState state;
        synchronized (this) { requireOpen(); state = collections.get(name); }
        if (state == null) return null;
        try { return state.read(); }
        catch (VectorDbException e) { if (e.getCode() == ErrorCode.NOT_FOUND) return null; throw e; }
    }
    public void remove(String name) {
        CollectionState state;
        synchronized (this) { state = forWrite(name); collections.remove(name); }
        state.close();
    }
    public List<CollectionInfo> list() {
        List<CollectionState> states;
        synchronized (this) { requireOpen(); states = List.copyOf(collections.values()); }
        var result = new ArrayList<CollectionInfo>();
        for (var state : states) {
            try (var lease = state.read()) { result.add(lease.info()); }
            catch (VectorDbException e) { if (e.getCode() != ErrorCode.NOT_FOUND) throw e; }
        }
        result.sort(Comparator.comparing(info -> info.config().name()));
        return List.copyOf(result);
    }

    public record Recovery(long lastAppliedSeq, long nextCollectionId) {}

    public Recovery recover(StorageEngine engine, boolean sync) {
        byte[] format = engine.get(SYSTEM, key("format_version"));
        if (format == null) {
            for (var namespace : Namespace.values()) try (var cursor = engine.scan(namespace, null, null)) {
                if (cursor.hasNext()) throw corrupt("Unversioned nonempty database");
            }
            try (var batch = engine.newBatch()) {
                batch.put(SYSTEM, key("format_version"), ValueCodec.encodeLong(1));
                batch.put(SYSTEM, key("last_applied_seq"), ValueCodec.encodeLong(0));
                batch.put(SYSTEM, key("next_collection_id"), ValueCodec.encodeLong(0));
                engine.commit(batch, sync);
            }
        } else if (ValueCodec.decodeLong(format) != 1) throw corrupt("Unsupported storage format");
        long last = requiredCounter(engine, "last_applied_seq"), next = requiredCounter(engine, "next_collection_id");
        var ids = new HashSet<Long>();
        try (var catalog = engine.scan(CATALOG, null, null)) {
            while (catalog.hasNext()) {
                var row = catalog.next();
                var value = ValueCodec.decodeCatalog(row.value());
                if (!KeyCodec.decodeCatalogKey(row.key()).equals(value.config().name()) || !ids.add(value.id()) || value.id() >= next)
                    throw corrupt("Inconsistent catalog identity");
                var index = new FlatIndex(value.config().dimension(), value.config().metric());
                var snapshot = engine.snapshot();
                try {
                    byte[] prefix = KeyCodec.collectionPrefix(value.id());
                    try (var vectors = snapshot.scan(VECTORS, prefix, KeyCodec.prefixUpperBound(prefix))) {
                        while (vectors.hasNext()) {
                            var vectorRow = vectors.next();
                            var internal = KeyCodec.decodeVectorKey(vectorRow.key());
                            if (internal.internalId() >= value.nextInternalId()) throw corrupt("Invalid internal ID counter");
                            byte[] external = snapshot.get(INT_TO_EXT, vectorRow.key());
                            if (external == null) continue; // Orphaned raw vector is not live, per recovery spec.
                            String externalId = KeyCodec.decodeSystemKey(external);
                            byte[] reverse = snapshot.get(EXT_TO_INT, KeyCodec.externalKey(value.id(), externalId));
                            if (reverse == null || ValueCodec.decodeLong(reverse) != internal.internalId()) throw corrupt("Inconsistent ID mapping");
                            byte[] metadata = snapshot.get(METADATA, vectorRow.key());
                            if (metadata == null) throw corrupt("Missing vector metadata");
                            ValueCodec.decodeMetadata(metadata);
                            float[] vector = ValueCodec.decodeVector(vectorRow.value(), value.config().dimension());
                            if (value.config().metric() == io.artemis.api.model.DistanceMetric.COSINE) {
                                double norm = 0;
                                for (float component : vector) norm += (double) component * component;
                                if (Math.abs(norm - 1) > 1e-5) throw corrupt("Stored cosine vector is not normalized");
                            }
                            index.insert(internal.internalId(), vector);
                        }
                    }
                    verifyMappings(snapshot, value.id(), prefix);
                    if (index.size() != value.vectorCount()) throw corrupt("Catalog vector count mismatch");
                    add(new CollectionState(value.id(), value.config(), value.nextInternalId(), value.vectorCount(), index, engine));
                } finally { snapshot.close(); }
            }
        } catch (RuntimeException | Error e) { close(); throw e; }
        return new Recovery(last, next);
    }

    private static void verifyMappings(StorageEngine.Snapshot snapshot, long collectionId, byte[] prefix) {
        byte[] end = KeyCodec.prefixUpperBound(prefix);
        try (var mappings = snapshot.scan(EXT_TO_INT, prefix, end)) {
            while (mappings.hasNext()) {
                var entry = mappings.next();
                String external = KeyCodec.decodeExternalKey(entry.key()).externalId();
                byte[] internalKey = KeyCodec.vectorKey(collectionId, ValueCodec.decodeLong(entry.value()));
                byte[] reverse = snapshot.get(INT_TO_EXT, internalKey);
                if (reverse == null || !KeyCodec.decodeSystemKey(reverse).equals(external)
                        || snapshot.get(VECTORS, internalKey) == null) throw corrupt("Inconsistent external ID mapping");
            }
        }
        try (var mappings = snapshot.scan(INT_TO_EXT, prefix, end)) {
            while (mappings.hasNext()) {
                var entry = mappings.next();
                long internal = KeyCodec.decodeVectorKey(entry.key()).internalId();
                byte[] reverse = snapshot.get(EXT_TO_INT, KeyCodec.externalKey(collectionId, KeyCodec.decodeSystemKey(entry.value())));
                if (reverse == null || ValueCodec.decodeLong(reverse) != internal || snapshot.get(VECTORS, entry.key()) == null)
                    throw corrupt("Inconsistent internal ID mapping");
            }
        }
    }

    private static long requiredCounter(StorageEngine engine, String name) {
        byte[] value = engine.get(SYSTEM, key(name));
        if (value == null) throw corrupt("Missing system counter: " + name);
        return ValueCodec.decodeLong(value);
    }
    public static byte[] key(String name) { return KeyCodec.systemKey(name); }
    private static VectorDbException corrupt(String message) { return new VectorDbException(ErrorCode.INTERNAL, message); }
    private void requireOpen() { if (closed) throw new VectorDbException(ErrorCode.UNAVAILABLE, "Catalog is closed"); }
    @Override public void close() {
        List<CollectionState> states;
        synchronized (this) {
            if (closed) return;
            closed = true; states = List.copyOf(collections.values()); collections.clear();
        }
        RuntimeException failure = null;
        for (var state : states) {
            try { state.close(); }
            catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        if (failure != null) throw failure;
    }
}
