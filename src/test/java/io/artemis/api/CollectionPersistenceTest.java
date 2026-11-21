package io.artemis.api;

import io.artemis.api.error.ErrorCode;
import io.artemis.api.error.VectorDbException;
import io.artemis.api.model.*;
import io.artemis.command.*;
import io.artemis.config.*;
import io.artemis.storage.*;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static io.artemis.api.CollectionServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

class CollectionPersistenceTest {
    @TempDir Path directory;

    ApplicationContext open() {
        return ApplicationContext.run(Map.of("vectordb.enabled", true, "vectordb.data-dir", directory.toString()), "test");
    }

    @Test
    void micronautRecoversCatalogVectorsCountersAndNormalizedSearchAfterRestart() {
        long originalCollectionId, sequence;
        List<SearchHit> before;
        try (var context = open()) {
            var service = context.getBean(CollectionService.class);
            originalCollectionId = service.createCollection(config("c", DistanceMetric.COSINE)).id();
            sequence = service.upsertBatch("c", List.of(row("x", 3, 4), row("y", -1, 0))).sequence();
            before = service.search(query("c", new float[]{1, 0}, 10, true));
        }
        try (var context = open()) {
            var service = context.getBean(CollectionService.class);
            assertEquals(originalCollectionId, service.getCollection("c").id());
            assertEquals(2, service.getCollection("c").vectorCount());
            assertArrayEquals(new float[]{0.6f, 0.8f}, service.get("c", "x").orElseThrow().vector(), 1e-6f);
            var after = service.search(query("c", new float[]{1, 0}, 10, true));
            assertEquals(before.stream().map(SearchHit::id).toList(), after.stream().map(SearchHit::id).toList());
            for (int i = 0; i < before.size(); i++) assertEquals(before.get(i).distance(), after.get(i).distance());
            assertTrue(service.upsert("c", row("z", 1, 1)).sequence() > sequence);
            var applier = context.getBean(CommandApplier.class);
            assertInstanceOf(Rejected.class, applier.apply(100, new CreateCollectionCmd(config("c", DistanceMetric.L2))));
        }
        try (var context = open()) {
            var applier = context.getBean(CommandApplier.class);
            assertEquals(100, applier.lastAppliedSeq());
            assertInstanceOf(AlreadyApplied.class, applier.apply(100, new DropCollectionCmd("c")));
            var service = context.getBean(CollectionService.class);
            assertEquals(3, service.getCollection("c").vectorCount());
            service.dropCollection("c");
            assertTrue(service.createCollection(config("c", DistanceMetric.L2)).id() > originalCollectionId);
            assertTrue(service.get("c", "x").isEmpty());
        }
    }

    @Test
    void unsupportedStorageFormatFailsRecoveryAndReleasesDatabaseLock() {
        try (var engine = new RocksDbStorageEngine(directory); var batch = engine.newBatch()) {
            batch.put(Namespace.SYSTEM, KeyCodec.systemKey("format_version"), ValueCodec.encodeLong(999));
            engine.commit(batch, true);
        }
        try (var config = ApplicationContext.run(Map.of("vectordb.enabled", false));
             var lifecycle = new EngineLifecycle(new RocksDbStorageEngine(directory), config.getBean(RequestValidator.class), config.getBean(WriterConfig.class))) {
            assertEquals(EngineLifecycle.State.STARTING, lifecycle.state());
            code(ErrorCode.UNAVAILABLE, lifecycle::requireReady);
            code(ErrorCode.INTERNAL, lifecycle::start);
            assertEquals(EngineLifecycle.State.CLOSING, lifecycle.state());
        }
        try (var reopened = new RocksDbStorageEngine(directory)) {
            assertEquals(999, ValueCodec.decodeLong(reopened.get(Namespace.SYSTEM, KeyCodec.systemKey("format_version"))));
        }
    }

    @Test
    void malformedCatalogFailsRecoveryWithoutAcceptingRequests() {
        try (var context = open()) { context.getBean(CollectionService.class).createCollection(config("c", DistanceMetric.L2)); }
        try (var engine = new RocksDbStorageEngine(directory); var batch = engine.newBatch()) {
            batch.put(Namespace.CATALOG, KeyCodec.catalogKey("c"), new byte[]{1});
            engine.commit(batch, true);
        }
        assertThrows(RuntimeException.class, this::open);
        try (var ignored = new RocksDbStorageEngine(directory)) { /* Failed startup released native resources. */ }
    }

    @Test
    void aliasMappingCannotBeRecoveredAsAnotherVectorsExternalId() {
        long id;
        try (var context = open()) {
            var service = context.getBean(CollectionService.class);
            id = service.createCollection(config("c", DistanceMetric.L2)).id();
            service.upsert("c", row("original", 1, 0));
        }
        try (var engine = new RocksDbStorageEngine(directory); var batch = engine.newBatch()) {
            batch.put(Namespace.EXT_TO_INT, KeyCodec.externalKey(id, "alias"), ValueCodec.encodeLong(0));
            engine.commit(batch, true);
        }
        assertRecoveryInternal();
    }

    @Test
    void nonunitStoredCosineVectorCannotSilentlyChangeSearchMeaning() {
        long id;
        try (var context = open()) {
            var service = context.getBean(CollectionService.class);
            id = service.createCollection(config("c", DistanceMetric.COSINE)).id();
            service.upsert("c", row("x", 1, 0));
        }
        try (var engine = new RocksDbStorageEngine(directory); var batch = engine.newBatch()) {
            batch.put(Namespace.VECTORS, KeyCodec.vectorKey(id, 0), ValueCodec.encodeVector(new float[]{2, 0}));
            engine.commit(batch, true);
        }
        assertRecoveryInternal();
    }

    @Test
    void malformedStoredKeyMapsToInternal() {
        try (var context = open()) { context.getBean(CollectionService.class).createCollection(config("c", DistanceMetric.L2)); }
        try (var engine = new RocksDbStorageEngine(directory); var batch = engine.newBatch()) {
            batch.put(Namespace.CATALOG, new byte[]{(byte) 0xff}, ValueCodec.encodeCatalog(1, config("bad", DistanceMetric.L2), 0, 0));
            batch.put(Namespace.SYSTEM, KeyCodec.systemKey("next_collection_id"), ValueCodec.encodeLong(2));
            engine.commit(batch, true);
        }
        assertRecoveryInternal();
    }

    private void assertRecoveryInternal() {
        try (var config = ApplicationContext.run(Map.of("vectordb.enabled", false));
             var lifecycle = new EngineLifecycle(new RocksDbStorageEngine(directory), config.getBean(RequestValidator.class), config.getBean(WriterConfig.class))) {
            code(ErrorCode.INTERNAL, lifecycle::start);
        }
    }
}
