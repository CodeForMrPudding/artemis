package io.artemis.api;

import io.artemis.api.error.ErrorCode;
import io.artemis.api.error.VectorDbException;
import io.artemis.api.model.*;
import io.artemis.command.UpsertBatchCmd;
import io.artemis.config.EngineLifecycle;
import io.artemis.config.WriterConfig;
import io.artemis.storage.RocksDbStorageEngine;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Executable specification examples through the public API and disposable real RocksDB. */
class CollectionSpecDemoTest {
    @TempDir Path directory;
    ApplicationContext context;
    CollectionService service;

    @BeforeEach void open() {
        context = ApplicationContext.run(Map.of("vectordb.enabled", true,
                "vectordb.data-dir", directory.resolve("main").toString()), "test");
        service = context.getBean(CollectionService.class);
        service.createCollection(config("c", 2, DistanceMetric.L2));
        service.createCollection(config("cos", 2, DistanceMetric.COSINE));
        service.upsert("c", row("original", 3, 4));
    }

    @AfterEach void close() { if (context != null) context.close(); }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void invalidPublicRequestsLeaveStoredDataUnchanged(String label, Consumer<CollectionService> request) {
        code(ErrorCode.INVALID_ARGUMENT, () -> request.accept(service));
        assertEquals(1, service.getCollection("c").vectorCount());
        assertArrayEquals(new float[]{3, 4}, service.get("c", "original").orElseThrow().vector());
        assertEquals(List.of("original"), service.search(search("c", new float[]{1, 0}, 10, null, true, true))
                .stream().map(SearchHit::id).toList());
        assertEquals(0, service.getCollection("cos").vectorCount());
        assertEquals(2, service.listCollections().size());
    }

    static Stream<Arguments> invalidRequests() {
        var cases = new ArrayList<Arguments>();
        for (String name : Arrays.asList(null, "", "bad name", "?", "café", "a\n", "a".repeat(65)))
            add(cases, "V01 collection name " + printable(name), s -> s.createCollection(config(name, 2, DistanceMetric.L2)));
        for (int dim : new int[]{0, -1, 4097})
            add(cases, "V02 dimension " + dim, s -> s.createCollection(config("bad", dim, DistanceMetric.L2)));
        add(cases, "V03 null metric", s -> s.createCollection(config("bad", 2, null)));
        add(cases, "V03 null HNSW", s -> s.createCollection(new CollectionConfig("bad", 2, DistanceMetric.L2, null)));
        for (int bad : new int[]{0, -1}) {
            add(cases, "V03 m " + bad, s -> s.createCollection(new CollectionConfig("bad", 2, DistanceMetric.L2, new HnswParams(bad, 1, 1))));
            add(cases, "V03 efConstruction " + bad, s -> s.createCollection(new CollectionConfig("bad", 2, DistanceMetric.L2, new HnswParams(1, bad, 1))));
            add(cases, "V03 efSearch " + bad, s -> s.createCollection(new CollectionConfig("bad", 2, DistanceMetric.L2, new HnswParams(1, 1, bad))));
        }
        for (String id : Arrays.asList(null, "", "é".repeat(128) + "x", "\uD800"))
            add(cases, "V04 vector ID " + printable(id), s -> s.upsert("c", new VectorRecord(id, new float[]{1, 0}, null)));
        var vectors = Arrays.asList(null, new float[0], new float[]{1}, new float[4097],
                new float[]{Float.NaN, 0}, new float[]{Float.POSITIVE_INFINITY, 0}, new float[]{Float.NEGATIVE_INFINITY, 0});
        String[] vectorLabels = {"null", "empty", "wrong dimension", "oversize", "NaN", "+infinity", "-infinity"};
        for (int i = 0; i < vectors.size(); i++) {
            float[] vector = vectors.get(i);
            add(cases, "V05 insert vector " + vectorLabels[i], s -> s.upsertBatch("c", Arrays.asList(row("new", 1, 0), new VectorRecord("bad", vector, null))));
            add(cases, "V05 query vector " + vectorLabels[i], s -> s.search(search("c", vector, 1, null, false, false)));
        }
        add(cases, "V06 zero cosine insert", s -> s.upsert("cos", row("zero", 0, -0f)));
        add(cases, "V06 zero cosine query", s -> s.search(search("cos", new float[]{0, -0f}, 1, null, false, false)));
        add(cases, "V06 invalid earlier cosine duplicate", s -> s.upsertBatch("cos", List.of(row("dup", 0, 0), row("dup", 1, 0))));
        List<Map<String, String>> metadata = Arrays.asList(metadata(33), Map.of("k".repeat(65), "v"),
                Map.of("k", "v".repeat(1025)), Map.of("é".repeat(33), "v"), Map.of("k", "é".repeat(513)),
                Collections.singletonMap(null, "v"), Collections.singletonMap("k", null),
                Map.of("\uD800", "v"), Map.of("k", "\uDC00"));
        String[] metadataLabels = {"33 entries", "65 byte key", "1025 byte value", "UTF8 key overflow", "UTF8 value overflow",
                "null key", "null value", "malformed key", "malformed value"};
        for (int i = 0; i < metadata.size(); i++) {
            var map = metadata.get(i);
            add(cases, "V07 metadata " + metadataLabels[i], s -> s.upsertBatch("c", List.of(row("new", 1, 0), new VectorRecord("bad", new float[]{1, 0}, map))));
        }
        add(cases, "V08 null batch", s -> s.upsertBatch("c", null));
        add(cases, "V08 empty batch", s -> s.upsertBatch("c", List.of()));
        add(cases, "V08 1001 records", s -> s.upsertBatch("c", Collections.nCopies(1001, row("dup", 1, 0))));
        add(cases, "V08 null batch record", s -> s.upsertBatch("c", Arrays.asList(row("new", 1, 0), null)));
        add(cases, "V08 null upsert record", s -> s.upsert("c", null));
        add(cases, "V08 invalid earlier finite duplicate", s -> s.upsertBatch("c", List.of(row("dup", Float.NaN, 0), row("dup", 1, 0))));
        for (int k : new int[]{0, -1, 1001})
            add(cases, "V09 k " + k, s -> s.search(search("c", new float[]{1, 0}, k, null, false, false)));
        for (int ef : new int[]{0, -1})
            add(cases, "V10 efSearch " + ef, s -> s.search(search("c", new float[]{1, 0}, 1, ef, false, false)));
        add(cases, "V11 null collection config", s -> s.createCollection(null));
        add(cases, "V11 null search request", s -> s.search(null));
        return cases.stream();
    }

    @Test @DisplayName("B01 collection name accepts one and 64 allowed characters")
    void collectionNameBoundaries() {
        for (String name : List.of("a", "A_-09" + "x".repeat(59)))
            assertEquals(name, service.createCollection(config(name, 1, DistanceMetric.L2)).config().name());
    }

    @Test @DisplayName("B02 dimensions accept 1 and 4096 with positive minimum HNSW")
    void dimensionBoundaries() {
        for (int dim : new int[]{1, 4096}) {
            String name = "d" + dim;
            service.createCollection(new CollectionConfig(name, dim, DistanceMetric.L2, new HnswParams(1, 1, 1)));
            float[] vector = new float[dim]; vector[0] = 1;
            service.upsert(name, new VectorRecord("x", vector, null));
            assertArrayEquals(vector, service.get(name, "x").orElseThrow().vector());
        }
    }

    @Test @DisplayName("B03 vector ID accepts one byte and 256 bytes of UTF8")
    void idBoundaries() {
        for (String id : List.of("x", "😀".repeat(64))) {
            assertTrue(service.upsert("c", row(id, 1, 0)).created());
            assertEquals(id, service.get("c", id).orElseThrow().id());
        }
    }

    @Test @DisplayName("B04 metadata accepts 32 entries 64 byte keys 1024 byte values empty and null")
    void metadataBoundaries() {
        var map = metadata(31); map.put("é".repeat(32), "😀".repeat(256));
        service.upsert("c", new VectorRecord("limits", new float[]{1, 0}, map));
        assertEquals(map, service.get("c", "limits").orElseThrow().metadata());
        service.upsert("c", new VectorRecord("empty", new float[]{1, 0}, Map.of("", "")));
        assertEquals(Map.of("", ""), service.get("c", "empty").orElseThrow().metadata());
        service.upsert("c", new VectorRecord("null", new float[]{1, 0}, null));
        assertNull(service.get("c", "null").orElseThrow().metadata());
    }

    @Test @DisplayName("B05 batch accepts 1000 duplicate records and search accepts k 1000")
    void batchAndSearchBoundaries() {
        var result = service.upsertBatch("c", Collections.nCopies(1000, row("dup", 1, 0)));
        assertEquals(1, result.created()); assertEquals(0, result.updated());
        assertEquals(2, service.search(search("c", new float[]{1, 0}, 1000, null, false, false)).size());
    }

    @Test @DisplayName("B06 cosine stores normalized vectors and preserves caller buffers including subnormals")
    void cosineBoundaries() {
        float[] input = {3, 4};
        service.upsert("cos", new VectorRecord("x", input, null));
        assertArrayEquals(new float[]{3, 4}, input);
        assertArrayEquals(new float[]{0.6f, 0.8f}, service.get("cos", "x").orElseThrow().vector(), 1e-6f);
        var hits = service.search(search("cos", input, 1, null, false, true));
        assertArrayEquals(new float[]{0.6f, 0.8f}, hits.getFirst().vector(), 1e-6f);
        assertEquals(0, hits.getFirst().distance(), 1e-6f);
        assertArrayEquals(new float[]{3, 4}, input);
        service.upsert("cos", row("tiny", Float.MIN_VALUE, 0));
        assertArrayEquals(new float[]{1, 0}, service.get("cos", "tiny").orElseThrow().vector());
    }

    @Test @DisplayName("B07 L2 and inner product accept zero and finite extreme vectors")
    void finiteBoundaries() {
        for (DistanceMetric metric : List.of(DistanceMetric.L2, DistanceMetric.INNER_PRODUCT)) {
            String name = metric.name(); service.createCollection(config(name, 2, metric));
            service.upsert(name, row("zero", 0, 0)); service.upsert(name, row("large", Float.MAX_VALUE, -Float.MAX_VALUE));
            var hits = service.search(search(name, new float[]{Float.MAX_VALUE, Float.MIN_VALUE}, 2, null, false, false));
            assertEquals(2, hits.size()); assertTrue(hits.stream().noneMatch(h -> Float.isNaN(h.distance())));
        }
    }

    @Test @DisplayName("O01 create returns empty collection and retry returns ALREADY_EXISTS")
    void createContract() {
        var info = service.createCollection(config("new", 2, DistanceMetric.L2));
        assertEquals(0, info.vectorCount()); assertEquals("new", info.config().name());
        code(ErrorCode.ALREADY_EXISTS, () -> service.createCollection(config("new", 2, DistanceMetric.L2)));
    }

    @Test @DisplayName("O02 drop retries return NOT_FOUND and recreated name has new isolated ID")
    void dropContract() {
        long id = service.getCollection("c").id(); service.dropCollection("c");
        code(ErrorCode.NOT_FOUND, () -> service.dropCollection("c"));
        assertTrue(service.createCollection(config("c", 2, DistanceMetric.L2)).id() > id);
        assertTrue(service.get("c", "original").isEmpty());
        assertTrue(service.search(search("c", new float[]{1, 0}, 10, null, false, false)).isEmpty());
    }

    @Test @DisplayName("O03 getCollection is repeatable and missing collection returns NOT_FOUND")
    void getCollectionContract() {
        assertEquals(service.getCollection("c"), service.getCollection("c"));
        assertEquals(1, service.getCollection("c").vectorCount());
        code(ErrorCode.NOT_FOUND, () -> service.getCollection("missing"));
    }

    @Test @DisplayName("O04 listCollections is repeatable and sorted by name")
    void listContract() {
        service.createCollection(config("A", 2, DistanceMetric.L2));
        assertEquals(List.of("A", "c", "cos"), service.listCollections().stream().map(i -> i.config().name()).toList());
        assertEquals(service.listCollections(), service.listCollections());
    }

    @Test @DisplayName("O05 upsert returns created then updated retries preserve state and sequence advances")
    void upsertContract() {
        float[] vector = {1, 2}; var metadata = new HashMap<>(Map.of("tag", "original"));
        var first = service.upsert("c", new VectorRecord("x", vector, metadata));
        vector[0] = 99; metadata.put("tag", "changed");
        assertArrayEquals(new float[]{1, 2}, service.get("c", "x").orElseThrow().vector());
        assertEquals(Map.of("tag", "original"), service.get("c", "x").orElseThrow().metadata());
        var second = service.upsert("c", row("x", 1, 2));
        assertTrue(first.created()); assertFalse(second.created()); assertTrue(second.sequence() > first.sequence());
        assertFalse(service.upsert("c", row("x", 1, 2)).created());
        assertEquals(2, service.getCollection("c").vectorCount());
        code(ErrorCode.NOT_FOUND, () -> service.upsert("missing", row("x", 1, 0)));
    }

    @Test @DisplayName("O06 batch last duplicate wins unique counts retry and atomic rejection")
    void batchContract() {
        var records = List.of(row("original", 1, 0), row("x", 1, 0), row("x", 2, 3));
        var first = service.upsertBatch("c", records);
        assertEquals(1, first.created()); assertEquals(1, first.updated());
        assertArrayEquals(new float[]{2, 3}, service.get("c", "x").orElseThrow().vector());
        var retry = service.upsertBatch("c", records);
        assertEquals(0, retry.created()); assertEquals(2, retry.updated()); assertTrue(retry.sequence() > first.sequence());
        code(ErrorCode.INVALID_ARGUMENT, () -> service.upsertBatch("c", List.of(row("new", 1, 0), new VectorRecord("x", new float[]{1}, null))));
        code(ErrorCode.INVALID_ARGUMENT, () -> service.upsertBatch("c", List.of(new VectorRecord("dup", new float[]{1}, null), row("dup", 1, 0))));
        assertEquals(2, service.getCollection("c").vectorCount()); assertTrue(service.get("c", "new").isEmpty());
        assertArrayEquals(new float[]{2, 3}, service.get("c", "x").orElseThrow().vector());
        code(ErrorCode.NOT_FOUND, () -> service.upsertBatch("missing", records));
    }

    @Test @DisplayName("O07 get returns owned data absent ID is empty missing collection is NOT_FOUND")
    void getContract() {
        var record = service.get("c", "original").orElseThrow(); record.vector()[0] = 99;
        assertArrayEquals(new float[]{3, 4}, service.get("c", "original").orElseThrow().vector());
        assertTrue(service.get("c", "absent").isEmpty());
        code(ErrorCode.NOT_FOUND, () -> service.get("missing", "original"));
    }

    @Test @DisplayName("O08 delete reports existed and repeat or absent delete is safe")
    void deleteContract() {
        var first = service.delete("c", "original"); var retry = service.delete("c", "original");
        assertTrue(first.existed()); assertFalse(retry.existed()); assertTrue(retry.sequence() > first.sequence());
        assertFalse(service.delete("c", "absent").existed()); assertEquals(0, service.getCollection("c").vectorCount());
        assertTrue(service.get("c", "original").isEmpty());
        code(ErrorCode.NOT_FOUND, () -> service.delete("missing", "x"));
    }

    @Test @DisplayName("O09 search empty fewer than k internal ID ties default and below k efSearch")
    void searchContract() {
        service.createCollection(config("empty", 2, DistanceMetric.L2));
        assertTrue(service.search(search("empty", new float[]{1, 0}, 3, null, false, false)).isEmpty());
        service.upsertBatch("empty", List.of(row("z", 1, 0), row("a", 1, 0), row("far", 0, 1)));
        for (Integer ef : Arrays.asList(null, 1)) {
            var hits = service.search(search("empty", new float[]{1, 0}, 10, ef, false, false));
            assertEquals(List.of("z", "a", "far"), hits.stream().map(SearchHit::id).toList());
            assertEquals(List.of(0f, 0f, 2f), hits.stream().map(SearchHit::distance).toList());
        }
        code(ErrorCode.NOT_FOUND, () -> service.search(search("missing", new float[]{1, 0}, 1, null, false, false)));
    }

    @ParameterizedTest(name = "S01 {0} metadata={1} vector={2}")
    @MethodSource("searchProfiles")
    void searchProfiles(DistanceMetric metric, boolean includeMetadata, boolean includeVector) {
        String name = "profile"; service.createCollection(config(name, 2, metric));
        service.upsert(name, row("x", 3, 4));
        var hit = service.search(search(name, new float[]{3, 4}, 1, null, includeMetadata, includeVector)).getFirst();
        assertEquals("x", hit.id());
        assertEquals(metric == DistanceMetric.INNER_PRODUCT ? -25f : 0f, hit.distance(), 1e-6f);
        if (includeMetadata) assertEquals(Map.of("tag", "x"), hit.metadata()); else assertNull(hit.metadata());
        if (includeVector) {
            float[] expected = metric == DistanceMetric.COSINE ? new float[]{0.6f, 0.8f} : new float[]{3, 4};
            assertArrayEquals(expected, hit.vector(), 1e-6f); hit.vector()[0] = 99;
            assertArrayEquals(expected, service.get(name, "x").orElseThrow().vector(), 1e-6f);
        } else assertNull(hit.vector());
    }

    static Stream<Arguments> searchProfiles() {
        return Arrays.stream(DistanceMetric.values()).flatMap(metric -> Stream.of(
                Arguments.of(metric, false, false), Arguments.of(metric, false, true),
                Arguments.of(metric, true, false), Arguments.of(metric, true, true)));
    }

    @Test @DisplayName("F01 STARTING public methods return UNAVAILABLE before recovery")
    void startingRejectsAllMethods() {
        try (var lifecycle = new EngineLifecycle(new RocksDbStorageEngine(directory.resolve("starting")),
                context.getBean(RequestValidator.class), context.getBean(WriterConfig.class))) {
            assertEquals(EngineLifecycle.State.STARTING, lifecycle.state());
            assertAllUnavailable(new CollectionServiceImpl(lifecycle, context.getBean(RequestValidator.class),
                    lifecycle.applier(), lifecycle.registry(), context.getBean(WriterConfig.class)));
        }
    }

    @Test @DisplayName("F02 CLOSING public methods return UNAVAILABLE after shutdown")
    void closingRejectsAllMethods() {
        context.getBean(EngineLifecycle.class).close(); assertAllUnavailable(service);
    }

    @Test @DisplayName("F03 queue fault injection public upsert returns RESOURCE_EXHAUSTED on real RocksDB")
    void queueOverflowThroughPublicApi() throws Exception {
        var writer = new WriterConfig() {
            public int getQueueCapacity() { return 1; }
            public boolean isSyncWrites() { return true; }
            public Duration getRequestTimeout() { return Duration.ofSeconds(5); }
        };
        var storage = new GatedStorageEngine(new RocksDbStorageEngine(directory.resolve("queue")));
        try (var lifecycle = new EngineLifecycle(storage, context.getBean(RequestValidator.class), writer)) {
            lifecycle.start();
            var api = new CollectionServiceImpl(lifecycle, context.getBean(RequestValidator.class), lifecycle.applier(), lifecycle.registry(), writer);
            api.createCollection(config("q", 2, DistanceMetric.L2));
            CompletableFuture<UpsertResult> first;
            CompletableFuture<?> second;
            try (var gate = storage.pauseCommit()) {
                first = CompletableFuture.supplyAsync(() -> api.upsert("q", row("first", 1, 0)));
                gate.awaitEntry();
                // Fill the one-slot queue deterministically while the public call owns the writer.
                second = lifecycle.applier().submit(new UpsertBatchCmd("q", List.of(row("second", 0, 1))));
                code(ErrorCode.RESOURCE_EXHAUSTED, () -> api.upsert("q", row("overflow", 1, 1)));
                code(ErrorCode.RESOURCE_EXHAUSTED, () -> api.upsertBatch("q", List.of(row("batch-overflow", 1, 1))));
            }
            first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            assertEquals(2, api.getCollection("q").vectorCount()); assertTrue(api.get("q", "overflow").isEmpty());
        }
    }

    private static void assertAllUnavailable(CollectionService api) {
        for (Runnable call : List.<Runnable>of(() -> api.createCollection(config("x", 2, DistanceMetric.L2)),
                () -> api.dropCollection("c"), () -> api.getCollection("c"), api::listCollections,
                () -> api.upsert("c", row("x", 1, 0)), () -> api.upsertBatch("c", List.of(row("x", 1, 0))),
                () -> api.get("c", "x"), () -> api.delete("c", "x"),
                () -> api.search(search("c", new float[]{1, 0}, 1, null, false, false))))
            code(ErrorCode.UNAVAILABLE, call);
    }

    private static CollectionConfig config(String name, int dim, DistanceMetric metric) {
        return new CollectionConfig(name, dim, metric, HnswParams.defaults());
    }
    private static VectorRecord row(String id, float a, float b) { return new VectorRecord(id, new float[]{a, b}, Map.of("tag", id)); }
    private static SearchRequest search(String name, float[] query, int k, Integer ef, boolean metadata, boolean vector) {
        return new SearchRequest(name, query, k, ef, metadata, vector);
    }
    private static Map<String, String> metadata(int entries) {
        var result = new LinkedHashMap<String, String>();
        for (int i = 0; i < entries; i++) result.put("k" + i, "v" + i);
        return result;
    }
    private static void code(ErrorCode expected, Runnable action) {
        assertEquals(expected, assertThrows(VectorDbException.class, action::run).getCode());
    }
    private static void add(List<Arguments> cases, String name, Consumer<CollectionService> action) { cases.add(Arguments.of(name, action)); }
    private static String printable(String value) {
        if (value == null) return "null";
        if (value.isEmpty()) return "empty";
        if (value.length() > 64) return "over limit " + value.length() + " code units";
        if (value.equals("\uD800")) return "malformed Unicode";
        return value.replace("\n", "\\n");
    }
}
