package io.artemis.api;

import io.artemis.api.error.*;
import io.artemis.api.model.*;
import io.artemis.config.*;
import io.artemis.storage.InMemoryStorageEngine;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CollectionServiceTest {
    ApplicationContext context;
    EngineLifecycle lifecycle;
    CollectionService service;

    @BeforeEach
    void setup() {
        context = ApplicationContext.run(Map.of("vectordb.enabled", false));
        var validator = context.getBean(RequestValidator.class);
        var writer = context.getBean(WriterConfig.class);
        lifecycle = new EngineLifecycle(new InMemoryStorageEngine(), validator, writer);
        lifecycle.start();
        service = new CollectionServiceImpl(lifecycle, validator, lifecycle.applier(), lifecycle.registry(), writer);
    }

    @AfterEach
    void close() {
        if (lifecycle != null) lifecycle.close();
        if (context != null) context.close();
    }

    @Test
    void collectionOperationsAndErrorsFollowThePublicContract() {
        assertTrue(service.listCollections().isEmpty());
        var created = service.createCollection(config("z", DistanceMetric.L2));
        assertEquals(0, created.vectorCount());
        assertEquals(created, service.getCollection("z"));
        code(ErrorCode.ALREADY_EXISTS, () -> service.createCollection(config("z", DistanceMetric.L2)));
        service.createCollection(config("a", DistanceMetric.COSINE));
        assertEquals(List.of("a", "z"), service.listCollections().stream().map(i -> i.config().name()).toList());
        service.dropCollection("z");
        code(ErrorCode.NOT_FOUND, () -> service.getCollection("z"));
        code(ErrorCode.NOT_FOUND, () -> service.dropCollection("z"));
        var recreated = service.createCollection(config("z", DistanceMetric.INNER_PRODUCT));
        assertTrue(recreated.id() > created.id());
        code(ErrorCode.NOT_FOUND, () -> service.get("missing", "x"));
        code(ErrorCode.NOT_FOUND, () -> service.delete("missing", "x"));
        code(ErrorCode.NOT_FOUND, () -> service.search(query("missing", new float[]{1, 0}, 1, true)));
        code(ErrorCode.INVALID_ARGUMENT, () -> service.upsert("z", null));
    }

    @Test
    void upsertCopiesInputsCleansOldIdsAndCountsFinalUniqueRecords() {
        service.createCollection(config("c", DistanceMetric.L2));
        float[] vector = {3, 4};
        var metadata = new HashMap<>(Map.of("label", "original"));
        var first = service.upsert("c", new VectorRecord("x", vector, metadata));
        assertTrue(first.created());
        vector[0] = 99;
        metadata.put("label", "changed");
        var stored = service.get("c", "x").orElseThrow();
        assertArrayEquals(new float[]{3, 4}, stored.vector());
        assertEquals(Map.of("label", "original"), stored.metadata());
        stored.vector()[0] = 77;
        assertArrayEquals(new float[]{3, 4}, service.get("c", "x").orElseThrow().vector());
        var batch = service.upsertBatch("c", List.of(row("x", 1, 0), row("y", 0, 1), row("y", 2, 2)));
        assertEquals(1, batch.created());
        assertEquals(1, batch.updated());
        assertTrue(batch.sequence() > first.sequence());
        assertEquals(2, service.getCollection("c").vectorCount());
        assertArrayEquals(new float[]{2, 2}, service.get("c", "y").orElseThrow().vector());
        assertFalse(service.upsert("c", row("x", 1, 0)).created());
        assertEquals(2, service.search(query("c", new float[]{1, 0}, 10, true)).size());
        var deleted = service.delete("c", "x");
        assertTrue(deleted.existed());
        assertFalse(service.delete("c", "x").existed());
        assertTrue(service.get("c", "x").isEmpty());
        assertEquals(1, service.getCollection("c").vectorCount());
        service.dropCollection("c");
        service.createCollection(config("c", DistanceMetric.L2));
        assertTrue(service.get("c", "y").isEmpty());
    }

    @ParameterizedTest
    @EnumSource(DistanceMetric.class)
    void searchSortsByDistanceThenInternalIdAndHonorsInclusionFlags(DistanceMetric metric) {
        service.createCollection(config("c", metric));
        assertTrue(service.search(query("c", new float[]{1, 0}, 4, false)).isEmpty());
        service.upsertBatch("c", List.of(row("second-lexically", 1, 0), row("a", 1, 0), row("far", 0, 1)));
        var plain = service.search(query("c", new float[]{1, 0}, 10, false));
        assertEquals(List.of("second-lexically", "a", "far"), plain.stream().map(SearchHit::id).toList());
        assertNull(plain.getFirst().metadata());
        assertNull(plain.getFirst().vector());
        float expected = metric == DistanceMetric.INNER_PRODUCT ? -1 : 0;
        assertEquals(expected, plain.getFirst().distance(), 1e-6);
        var full = service.search(query("c", new float[]{1, 0}, 1, true));
        assertEquals(1, full.size());
        assertArrayEquals(new float[]{1, 0}, full.getFirst().vector());
        assertEquals(Map.of("tag", "second-lexically"), full.getFirst().metadata());
        full.getFirst().vector()[0] = 100;
        assertArrayEquals(new float[]{1, 0}, service.get("c", "second-lexically").orElseThrow().vector());
    }

    @Test
    void rejectsTheWholeBatchIncludingEarlierDuplicatesBeforeChangingState() {
        service.createCollection(config("c", DistanceMetric.COSINE));
        var original = service.upsert("c", row("original", 1, 0));
        code(ErrorCode.INVALID_ARGUMENT, () -> service.upsertBatch("c", List.of(row("new", 0, 1), new VectorRecord("bad", new float[]{1}, null))));
        code(ErrorCode.INVALID_ARGUMENT, () -> service.upsertBatch("c", List.of(row("dup", 0, 0), row("dup", 1, 0))));
        code(ErrorCode.INVALID_ARGUMENT, () -> service.search(query("c", new float[]{0, 0}, 1, false)));
        code(ErrorCode.INVALID_ARGUMENT, () -> service.search(query("c", new float[]{1}, 1, false)));
        assertEquals(1, service.getCollection("c").vectorCount());
        assertTrue(service.get("c", "new").isEmpty());
        assertTrue(service.upsert("c", row("original", 3, 4)).sequence() > original.sequence());
        assertArrayEquals(new float[]{0.6f, 0.8f}, service.get("c", "original").orElseThrow().vector(), 1e-6f);
    }

    @Test
    void closedLifecycleRejectsReadsAndWritesAsUnavailable() {
        lifecycle.close();
        code(ErrorCode.UNAVAILABLE, service::listCollections);
        code(ErrorCode.UNAVAILABLE, () -> service.createCollection(config("c", DistanceMetric.L2)));
    }

    @Test
    void missingCollectionUpsertIsRejectedOnTheSequencedWriter() {
        code(ErrorCode.NOT_FOUND, () -> service.upsert("missing", row("x", 1, 0)));
        assertEquals(1, lifecycle.applier().lastAppliedSeq());
        assertEquals(0, service.createCollection(config("c", DistanceMetric.L2)).id());
        assertEquals(2, lifecycle.applier().lastAppliedSeq());
    }

    static CollectionConfig config(String name, DistanceMetric metric) {
        return new CollectionConfig(name, 2, metric, HnswParams.defaults());
    }
    static VectorRecord row(String id, float a, float b) {
        return new VectorRecord(id, new float[]{a, b}, Map.of("tag", id));
    }
    static SearchRequest query(String name, float[] vector, int k, boolean include) {
        return new SearchRequest(name, vector, k, 1, include, include);
    }
    static void code(ErrorCode expected, Runnable operation) {
        assertEquals(expected, assertThrows(VectorDbException.class, operation::run).getCode());
    }
}
