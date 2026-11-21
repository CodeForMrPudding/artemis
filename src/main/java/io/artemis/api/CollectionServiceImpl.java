package io.artemis.api;

import io.artemis.api.error.*;
import io.artemis.api.model.*;
import io.artemis.catalog.CollectionRegistry;
import io.artemis.command.*;
import io.artemis.config.*;
import io.artemis.math.Distances;
import io.artemis.storage.*;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import java.util.*;
import java.util.concurrent.*;

import static io.artemis.storage.Namespace.*;

@Singleton
@Requires(property = "vectordb.enabled", notEquals = "false")
@RequiredArgsConstructor
public class CollectionServiceImpl implements CollectionService {
    private final EngineLifecycle lifecycle;
    private final RequestValidator validator;
    private final CommandApplier applier;
    private final CollectionRegistry registry;
    private final WriterConfig writerConfig;

    @Override public CollectionInfo createCollection(CollectionConfig config) {
        lifecycle.requireReady(); validator.validateCreateCollection(config);
        return await(applier.submit(new CreateCollectionCmd(config)));
    }
    @Override public void dropCollection(String name) {
        lifecycle.requireReady(); validator.validateCollectionName(name);
        await(applier.submit(new DropCollectionCmd(name)));
    }
    @Override public CollectionInfo getCollection(String name) {
        lifecycle.requireReady(); validator.validateCollectionName(name);
        try (var lease = registry.read(name)) { return lease.info(); }
    }
    @Override public List<CollectionInfo> listCollections() { lifecycle.requireReady(); return registry.list(); }
    @Override public UpsertResult upsert(String collection, VectorRecord record) {
        var result = upsertBatch(collection, Collections.singletonList(record));
        return new UpsertResult(result.created() == 1, result.sequence());
    }
    @Override public BatchUpsertResult upsertBatch(String collection, List<VectorRecord> records) {
        lifecycle.requireReady(); validator.validateUpsert(collection, records);
        var command = new UpsertBatchCmd(collection, records);
        try (var lease = registry.readIfPresent(collection)) {
            if (lease != null) for (var row : command.records()) validator.validateVectorForMetric(row.vector(), lease.info().config().metric());
        }
        return await(applier.submit(command));
    }
    @Override public Optional<VectorRecord> get(String collection, String id) {
        lifecycle.requireReady(); validator.validateCollectionName(collection); validator.validateId(id);
        try (var lease = registry.read(collection)) {
            var info = lease.info(); var snapshot = lease.snapshot();
            byte[] internal = snapshot.get(EXT_TO_INT, KeyCodec.externalKey(info.id(), id));
            if (internal == null) return Optional.empty();
            byte[] key = KeyCodec.vectorKey(info.id(), ValueCodec.decodeLong(internal));
            return Optional.of(new VectorRecord(id,
                    ValueCodec.decodeVector(required(snapshot, VECTORS, key), info.config().dimension()),
                    ValueCodec.decodeMetadata(required(snapshot, METADATA, key))));
        }
    }
    @Override public DeleteResult delete(String collection, String id) {
        lifecycle.requireReady(); validator.validateCollectionName(collection); validator.validateId(id);
        return await(applier.submit(new DeleteCmd(collection, id)));
    }
    @Override public List<SearchHit> search(SearchRequest request) {
        lifecycle.requireReady(); validator.validateSearch(request);
        float[] query = request.query().clone();
        try (var lease = registry.read(request.collection())) {
            var info = lease.info(); var config = info.config();
            validator.validateDimension(query, config.dimension()); validator.validateVectorForMetric(query, config.metric());
            if (config.metric() == DistanceMetric.COSINE) query = Distances.normalizedCopy(query);
            int ef = Math.max(request.k(), request.efSearch() == null ? config.hnsw().efSearch() : request.efSearch());
            var hits = new ArrayList<SearchHit>();
            for (var neighbor : lease.index().search(query, request.k(), ef)) {
                byte[] key = KeyCodec.vectorKey(info.id(), neighbor.internalId());
                var snapshot = lease.snapshot();
                String id = KeyCodec.decodeSystemKey(required(snapshot, INT_TO_EXT, key));
                hits.add(new SearchHit(id, neighbor.distance(),
                        request.includeMetadata() ? ValueCodec.decodeMetadata(required(snapshot, METADATA, key)) : null,
                        request.includeVector() ? ValueCodec.decodeVector(required(snapshot, VECTORS, key), config.dimension()) : null));
            }
            return List.copyOf(hits);
        }
    }
    private static byte[] required(StorageEngine.Snapshot snapshot, Namespace namespace, byte[] key) {
        byte[] value = snapshot.get(namespace, key);
        if (value == null) throw new VectorDbException(ErrorCode.INTERNAL, "Missing committed " + namespace.persistentName() + " entry");
        return value;
    }
    private <R> R await(CompletableFuture<ApplyOutcome<R>> future) {
        try {
            return switch (future.get(writerConfig.getRequestTimeout().toNanos(), TimeUnit.NANOSECONDS)) {
                case Applied<R> applied -> applied.result();
                case Rejected<R> rejected -> throw new VectorDbException(rejected.code(), rejected.message());
                case AlreadyApplied<R> ignored -> throw new VectorDbException(ErrorCode.INTERNAL, "Newly submitted command was already applied");
            };
        } catch (TimeoutException e) {
            throw new VectorDbException(ErrorCode.UNAVAILABLE, "Write outcome unknown: timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VectorDbException(ErrorCode.UNAVAILABLE, "Write outcome unknown: interrupted", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof VectorDbException db) throw db;
            throw new VectorDbException(ErrorCode.INTERNAL, "Command execution failed", e.getCause());
        }
    }
}
