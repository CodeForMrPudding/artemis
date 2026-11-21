package io.artemis.command;

import io.artemis.api.RequestValidator;
import io.artemis.api.error.*;
import io.artemis.api.model.*;
import io.artemis.catalog.*;
import io.artemis.index.FlatIndex;
import io.artemis.math.Distances;
import io.artemis.storage.*;
import java.util.*;

import static io.artemis.storage.Namespace.*;

/** Serialized state machine. Preparation has no effects until its one storage batch commits. */
final class CommandProcessor {
    private final StorageEngine engine;
    private final CollectionRegistry registry;
    private final RequestValidator validator;
    private final boolean sync;
    private long nextCollectionId;
    private volatile long lastAppliedSeq;

    CommandProcessor(StorageEngine engine, CollectionRegistry registry, RequestValidator validator, boolean sync) {
        this.engine = engine; this.registry = registry; this.validator = validator; this.sync = sync;
    }
    void initialize(CollectionRegistry.Recovery recovery) {
        lastAppliedSeq = recovery.lastAppliedSeq(); nextCollectionId = recovery.nextCollectionId();
    }
    long lastAppliedSeq() { return lastAppliedSeq; }

    @SuppressWarnings("unchecked") // The sealed command records bind their result type.
    <R> ApplyOutcome<R> apply(long sequence, Command<R> command) {
        if (sequence <= 0) throw new IllegalArgumentException("Sequence must be positive");
        if (sequence <= lastAppliedSeq) return new AlreadyApplied<>(sequence);
        try (var batch = engine.newBatch()) {
            Prepared prepared;
            try {
                if (command == null) throw new VectorDbException(ErrorCode.INVALID_ARGUMENT, "Command is required");
                prepared = switch (command) {
                    case CreateCollectionCmd create -> create(batch, create.config());
                    case DropCollectionCmd drop -> drop(batch, drop.name());
                    case UpsertBatchCmd upsert -> upsert(batch, upsert, sequence);
                    case DeleteCmd delete -> delete(batch, delete, sequence);
                };
            } catch (VectorDbException e) {
                if (e.getCode() == ErrorCode.INTERNAL || e.getCode() == ErrorCode.UNAVAILABLE) throw e;
                // Discard partially prepared operations; only the rejection's sequence is durable.
                try (var rejection = engine.newBatch()) {
                    putSequence(rejection, sequence);
                    engine.commit(rejection, sync);
                }
                lastAppliedSeq = sequence;
                return new Rejected<>(sequence, e.getCode(), e.getMessage());
            }
            putSequence(batch, sequence);
            if (prepared.state() != null) prepared.state().freezeForCommit();
            try {
                engine.commit(batch, sync);
                prepared.publish().run();
            } catch (RuntimeException | Error failure) {
                if (prepared.state() != null) {
                    try { prepared.state().invalidate(); }
                    catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                }
                throw failure;
            }
            lastAppliedSeq = sequence;
            return new Applied<>(sequence, (R) prepared.result());
        }
    }

    private record Prepared(Object result, CollectionState state, Runnable publish) {}

    private Prepared create(StorageEngine.WriteBatch batch, CollectionConfig config) {
        validator.validateCreateCollection(config);
        if (registry.contains(config.name())) throw failure(ErrorCode.ALREADY_EXISTS, "Collection already exists: " + config.name());
        long id = nextCollectionId;
        if (id == Long.MAX_VALUE) throw failure(ErrorCode.RESOURCE_EXHAUSTED, "Collection IDs exhausted");
        batch.put(CATALOG, KeyCodec.catalogKey(config.name()), ValueCodec.encodeCatalog(id, config, 0, 0));
        batch.put(SYSTEM, CollectionRegistry.key("next_collection_id"), ValueCodec.encodeLong(id + 1));
        return new Prepared(new CollectionInfo(id, config, 0), null, () -> {
            registry.add(new CollectionState(id, config, 0, 0, new FlatIndex(config.dimension(), config.metric()), engine));
            nextCollectionId = id + 1;
        });
    }

    private Prepared drop(StorageEngine.WriteBatch batch, String name) {
        validator.validateCollectionName(name);
        var state = registry.forWrite(name);
        batch.delete(CATALOG, KeyCodec.catalogKey(name));
        byte[] prefix = KeyCodec.collectionPrefix(state.id()), end = KeyCodec.prefixUpperBound(prefix);
        for (var namespace : List.of(VECTORS, METADATA, EXT_TO_INT, INT_TO_EXT)) batch.deleteRange(namespace, prefix, end);
        return new Prepared(null, state, () -> registry.remove(name));
    }

    private Prepared upsert(StorageEngine.WriteBatch batch, UpsertBatchCmd command, long sequence) {
        var records = command.records();
        validator.validateUpsert(command.collection(), records);
        var state = registry.forWrite(command.collection());
        // Authoritative checks cover every record before deduplication or allocation.
        for (var record : records) {
            validator.validateDimension(record.vector(), state.config().dimension());
            validator.validateVectorForMetric(record.vector(), state.config().metric());
        }
        var unique = new LinkedHashMap<String, VectorRecord>();
        for (var row : records) unique.put(row.id(), row);
        long next = state.nextInternalId(), count = state.vectorCount();
        int created = 0, updated = 0;
        var deleted = new ArrayList<Long>();
        var inserted = new ArrayList<CollectionState.Insertion>();
        for (var row : unique.values()) {
            if (next == Long.MAX_VALUE) throw failure(ErrorCode.RESOURCE_EXHAUSTED, "Internal IDs exhausted");
            byte[] external = KeyCodec.externalKey(state.id(), row.id());
            byte[] previous = engine.get(EXT_TO_INT, external);
            if (previous == null) { created++; count++; }
            else {
                updated++;
                long oldId = ValueCodec.decodeLong(previous);
                deleteInternal(batch, state.id(), oldId);
                deleted.add(oldId);
            }
            long internalId = next++;
            float[] vector = state.config().metric() == DistanceMetric.COSINE
                    ? Distances.normalizedCopy(row.vector()) : row.vector().clone();
            byte[] key = KeyCodec.vectorKey(state.id(), internalId);
            batch.put(VECTORS, key, ValueCodec.encodeVector(vector));
            batch.put(METADATA, key, ValueCodec.encodeMetadata(row.metadata()));
            batch.put(EXT_TO_INT, external, ValueCodec.encodeLong(internalId));
            batch.put(INT_TO_EXT, key, KeyCodec.systemKey(row.id()));
            inserted.add(new CollectionState.Insertion(internalId, vector));
        }
        batch.put(CATALOG, KeyCodec.catalogKey(command.collection()), ValueCodec.encodeCatalog(state.id(), state.config(), next, count));
        long finalNext = next, finalCount = count;
        return new Prepared(new BatchUpsertResult(created, updated, sequence), state,
                () -> state.publish(finalNext, finalCount, deleted, inserted));
    }

    private Prepared delete(StorageEngine.WriteBatch batch, DeleteCmd command, long sequence) {
        validator.validateCollectionName(command.collection()); validator.validateId(command.id());
        var state = registry.forWrite(command.collection());
        byte[] external = KeyCodec.externalKey(state.id(), command.id());
        byte[] previous = engine.get(EXT_TO_INT, external);
        if (previous == null) return new Prepared(new DeleteResult(false, sequence), null, () -> {});
        long oldId = ValueCodec.decodeLong(previous), count = state.vectorCount() - 1;
        if (count < 0) throw failure(ErrorCode.INTERNAL, "Negative vector count");
        deleteInternal(batch, state.id(), oldId);
        batch.delete(EXT_TO_INT, external);
        batch.put(CATALOG, KeyCodec.catalogKey(command.collection()), ValueCodec.encodeCatalog(state.id(), state.config(), state.nextInternalId(), count));
        return new Prepared(new DeleteResult(true, sequence), state,
                () -> state.publish(state.nextInternalId(), count, List.of(oldId), List.of()));
    }

    private void deleteInternal(StorageEngine.WriteBatch batch, long collection, long internal) {
        byte[] key = KeyCodec.vectorKey(collection, internal);
        batch.delete(VECTORS, key); batch.delete(METADATA, key); batch.delete(INT_TO_EXT, key);
    }
    private void putSequence(StorageEngine.WriteBatch batch, long sequence) {
        batch.put(SYSTEM, CollectionRegistry.key("last_applied_seq"), ValueCodec.encodeLong(sequence));
    }
    private static VectorDbException failure(ErrorCode code, String message) { return new VectorDbException(code, message); }
}
