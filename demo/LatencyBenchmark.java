import io.artemis.api.CollectionService;
import io.artemis.api.error.ErrorCode;
import io.artemis.api.error.VectorDbException;
import io.artemis.api.model.*;
import io.artemis.config.WriterConfig;
import io.micronaut.context.ApplicationContext;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Java 21 source launcher: java --class-path 'build/install/artemis/lib/*'
 * demo/LatencyBenchmark.java OUTPUT [vectors] [dimensions] [warmup] [samples].
 * Raw public-service call latencies only; reporting and percentiles are separate.
 */
public class LatencyBenchmark {
    private static final long SEED = 20261120L;
    private static final int BATCH_SIZE = 4;
    private static final int POOL_SIZE = 256;
    private final int vectors, dimensions, warmup, samples, k;
    private final Path output, database;
    private final List<Row> rows = Collections.synchronizedList(new ArrayList<>());
    private final List<Workload> workloads = new ArrayList<>();
    private final Map<String, Object> metadata = new LinkedHashMap<>();
    private final VectorRecord[] records;
    private final float[][] queries = new float[POOL_SIZE][];
    private CollectionService api;

    private record Row(String workload, int worker, int sample, long nanos, String outcome) {}
    private record Workload(String name, String description, String collection, int threads,
                            int initialVectors, int finalVectors, int batchSize,
                            IntFunction<Supplier<?>> prepare, BiConsumer<Integer, Object> validate,
                            ErrorCode expectedError) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 5) {
            throw new IllegalArgumentException("Expected <outputDirectory> [vectors=1000] [dimensions=128] [warmup=1000] [samples=5000]");
        }
        int vectors = argument(args, 1, 1000), dimensions = argument(args, 2, 128);
        int warmup = argument(args, 3, 1000), samples = argument(args, 4, 5000);
        require(vectors >= BATCH_SIZE, "vectors must be at least " + BATCH_SIZE);
        require(dimensions > 0 && dimensions <= 4096, "dimensions must be between 1 and 4096");
        require(warmup > 0 && samples > 0, "warmup and samples must be positive");
        new LatencyBenchmark(Path.of(args[0]), vectors, dimensions, warmup, samples).run();
    }

    private LatencyBenchmark(Path directory, int vectors, int dimensions, int warmup, int samples) throws IOException {
        this.vectors = vectors;
        this.dimensions = dimensions;
        this.warmup = warmup;
        this.samples = samples;
        this.k = Math.min(10, vectors);
        this.output = directory.toAbsolutePath().normalize();
        Files.createDirectories(output);
        for (String file : List.of("latencies.csv", "workloads.csv", "metadata.json")) {
            require(!Files.exists(output.resolve(file)), "Refusing to replace benchmark artifact: " + output.resolve(file));
        }
        // A unique, newly created child is the only database this process ever opens.
        database = Files.createTempDirectory(output, "benchmark-db-").toRealPath();
        records = new VectorRecord[vectors];
        var random = new Random(SEED);
        for (int i = 0; i < vectors; i++) records[i] = new VectorRecord("v" + i, vector(random), Map.of("label", "v" + i));
        for (int i = 0; i < queries.length; i++) queries[i] = vector(random);
        metadata.put("vectors", vectors);
        metadata.put("dimensions", dimensions);
        metadata.put("k", k);
        metadata.put("seed", SEED);
        metadata.put("warmup", warmup);
        metadata.put("samples", samples);
        metadata.put("sync_writes", true);
        metadata.put("arrival_model", "closed-loop");
        metadata.put("mixed_threads", 4);
        metadata.put("batch_size", BATCH_SIZE);
        metadata.put("query_pool_size", POOL_SIZE);
        metadata.put("update_pool_size", POOL_SIZE);
        metadata.put("input_methodology", "One java.util.Random seeded once generates N initial vectors, then 256 queries: uniform [-1,1) coordinates, followed by moving the first coordinate 0.01 away from zero. Initial IDs are v0..v(N-1); update pool slot i targets v(i mod N) with query i and preserves its metadata. Batch pool slot p targets four distinct IDs (4*p+j) mod N with queries (p+j) mod 256. Inputs are prepared outside timing and reused; each phase starts at pool slot zero.");
        metadata.put("jvm", System.getProperty("java.vm.name"));
        metadata.put("java_version", System.getProperty("java.version"));
        metadata.put("jvm_version", System.getProperty("java.vm.version"));
        metadata.put("os", System.getProperty("os.name"));
        metadata.put("os_version", System.getProperty("os.version"));
        metadata.put("arch", System.getProperty("os.arch"));
        metadata.put("processors", Runtime.getRuntime().availableProcessors());
        metadata.put("max_heap_bytes", Runtime.getRuntime().maxMemory());
        metadata.put("started_at", Instant.now().toString());
        metadata.put("database_directory", database.toString());
        metadata.put("timing_methodology", "System.nanoTime around one prepared CollectionService invocation; result validation, row retention, CSV I/O, setup and progress output are outside timing. Every attempted measured call is retained, including an unexpected exception or invalid result, and unexpected outcomes abort the run.");
        metadata.put("arrival_methodology", "Closed-loop with no injected delays: each caller waits for a call and its validation before issuing the next. Latencies include writer queue wait, synced RocksDB WAL commit and index publication where the API performs them. No coordinated-omission correction; this is not a fixed-rate load test.");
        metadata.put("state_methodology", "All collections are seeded before timing, using the same finite nonzero random records. Each mutating family has a separate collection. Update and batch histories append FlatIndex tombstones during warmup and measurement; paired insert/delete stays at N..N+1 live vectors. Paired create/drop uses an empty collection, repeatedly recreating it. No compaction or reset between warmup and measurement. Workload order is recorded, so cache/JIT/device history remains visible.");
        metadata.put("search_methodology", "Exact JVM FlatIndex; k=min(10,N), default efSearch, seeded pregenerated query pool of 256. Primary metric profiles request neither metadata nor vectors; search.L2.include_all requests both. Each search validates hit count, finite ordered distances, unique IDs and requested result fields after timing. Search-only collections have fixed cardinality and no update history.");
        metadata.put("mixed_methodology", "Four virtual-thread callers on one separately seeded L2 collection: two search readers, one get reader, one fixed-cardinality updater. Each performs warmup calls, then waits on a shared start gate before measurement. Each worker has the configured sample count. A barrier synchronizes measurement start, not subsequent arrivals; faster readers can finish before the writer. No claim of constant overlap or equal work rates.");
        metadata.put("scope", "Local JVM/RocksDB/FlatIndex baseline; no production, native-image, HNSW or latency SLO claim. Raw samples are reported independently; this harness computes no percentiles.");
    }

    private void run() throws Exception {
        metadata.put("status", "running");
        writeMetadata();
        try (var context = ApplicationContext.run(Map.of("vectordb.enabled", true,
                "vectordb.data-dir", database.toString(), "vectordb.writer.sync-writes", true))) {
            require(context.getBean(WriterConfig.class).isSyncWrites(), "sync writes must be enabled");
            api = context.getBean(CollectionService.class);
            setup();
            runReads();
            runWrites();
            runSearches();
            runRejections();
            runMixed();
            metadata.put("status", "complete");
        } catch (Throwable failure) {
            metadata.put("status", "failed");
            metadata.put("failure", failure.toString());
            throw failure;
        } finally {
            metadata.put("finished_at", Instant.now().toString());
            metadata.put("measured_rows", rows.size());
            metadata.put("workload_order", workloads.stream().map(Workload::name).toList());
            writeRows();
            writeWorkloads();
            writeMetadata();
        }
        System.out.println("Benchmark finished: " + rows.size() + " measured calls; artifacts in " + output);
    }

    private void setup() {
        System.out.println("Seeding isolated benchmark database: " + vectors + " vectors x " + dimensions + " dimensions");
        for (String collection : List.of("read_base", "update_base", "churn_base", "delete_base", "batch_base", "mixed_base")) {
            seed(collection, DistanceMetric.L2);
        }
        for (var metric : DistanceMetric.values()) seed("search_" + metric, metric);
        metadata.put("seeded_collections", api.listCollections().size());
    }

    private void seed(String name, DistanceMetric metric) {
        api.createCollection(config(name, metric));
        for (int first = 0; first < records.length; first += 128) {
            var batch = Arrays.asList(records).subList(first, Math.min(first + 128, records.length));
            var result = api.upsertBatch(name, batch);
            require(result.created() == batch.size() && result.updated() == 0, "Unexpected seed result");
        }
        require(api.getCollection(name).vectorCount() == vectors, "Unexpected seeded cardinality");
    }

    private void runReads() {
        single(ok("get.hit", "Read a seeded L2 vector and metadata", "read_base", 1,
                i -> { String id = records[i % vectors].id(); return () -> api.get("read_base", id); },
                (i, value) -> validateGet(value, records[i % vectors], true)));
        single(ok("get.missing", "Read an absent ID in an existing collection", "read_base", 1,
                i -> () -> api.get("read_base", "absent"),
                (i, value) -> require(((Optional<?>) value).isEmpty(), "Missing ID returned a record")));
        single(ok("getCollection", "Inspect fixed-cardinality collection", "read_base", 1,
                i -> () -> api.getCollection("read_base"),
                (i, value) -> require(((CollectionInfo) value).vectorCount() == vectors, "Unexpected collection size")));
        int collectionCount = api.listCollections().size();
        single(ok("listCollections", "List all " + collectionCount + " seeded collections sorted by name", "read_base", 1,
                i -> api::listCollections, (i, value) -> {
                    var all = (List<?>) value;
                    require(all.size() == collectionCount, "Unexpected list size");
                    String previous = "";
                    for (Object item : all) {
                        String name = ((CollectionInfo) item).config().name();
                        require(previous.compareTo(name) < 0, "Collection listing is not sorted/unique");
                        previous = name;
                    }
                }));
    }

    private void runWrites() {
        var updatePool = updatePool();
        single(ok("upsert.update", "Replace existing IDs; fixed live cardinality with growing tombstone history", "update_base", 1,
                i -> { var record = updatePool[i % POOL_SIZE]; return () -> api.upsert("update_base", record); },
                (i, value) -> require(!((UpsertResult) value).created(), "Update unexpectedly inserted")));
        var transientRecord = new VectorRecord("transient", queries[0], Map.of("label", "transient"));
        pair(ok("upsert.insert", "Insert transient ID, paired with delete.existing; live size N..N+1", "churn_base", 1,
                        i -> () -> api.upsert("churn_base", transientRecord),
                        (i, value) -> require(((UpsertResult) value).created(), "Insert unexpectedly updated")),
                ok("delete.existing", "Delete just-inserted transient ID; paired with upsert.insert", "churn_base", 1,
                        i -> () -> api.delete("churn_base", "transient"),
                        (i, value) -> require(((DeleteResult) value).existed(), "Existing delete missed")));
        single(ok("delete.missing", "Delete absent ID; no live-cardinality or index-history growth", "delete_base", 1,
                i -> () -> api.delete("delete_base", "absent"),
                (i, value) -> require(!((DeleteResult) value).existed(), "Missing delete found a record")));
        var batches = new ArrayList<List<VectorRecord>>();
        for (int p = 0; p < POOL_SIZE; p++) {
            var batch = new ArrayList<VectorRecord>();
            for (int j = 0; j < BATCH_SIZE; j++) {
                int id = (p * BATCH_SIZE + j) % vectors;
                batch.add(new VectorRecord(records[id].id(), queries[(p + j) % POOL_SIZE], Map.of("label", records[id].id())));
            }
            batches.add(List.copyOf(batch));
        }
        single(ok("batch.update", "Atomic batch of four distinct existing IDs; growing tombstone history", "batch_base", BATCH_SIZE,
                i -> { var batch = batches.get(i % POOL_SIZE); return () -> api.upsertBatch("batch_base", batch); },
                (i, value) -> {
                    var result = (BatchUpsertResult) value;
                    require(result.created() == 0 && result.updated() == BATCH_SIZE, "Unexpected batch counts");
                }));
        var transientConfig = config("transient_collection", DistanceMetric.L2);
        pair(new Workload("createCollection", "Create an empty collection, paired with dropCollection", null, 1, 0, 0, 1,
                        i -> () -> api.createCollection(transientConfig),
                        (i, value) -> require(((CollectionInfo) value).vectorCount() == 0, "New collection is not empty"), null),
                new Workload("dropCollection", "Drop the just-created empty collection", null, 1, 0, 0, 1,
                        i -> () -> { api.dropCollection("transient_collection"); return null; },
                        (i, value) -> require(value == null, "Unexpected drop result"), null));
    }

    private void runSearches() {
        for (var metric : DistanceMetric.values()) {
            single(searchWorkload("search." + metric, "search_" + metric, false, 1));
        }
        single(searchWorkload("search.L2.include_all", "search_L2", true, 1));
    }

    private void runRejections() {
        float[] wrongDimension = new float[dimensions == 1 ? 2 : dimensions - 1];
        Arrays.fill(wrongDimension, 1f);
        var badDimension = new VectorRecord("invalid", wrongDimension, null);
        var nan = queries[0].clone(); nan[0] = Float.NaN;
        var infinity = queries[0].clone(); infinity[0] = Float.POSITIVE_INFINITY;
        var nanRecord = new VectorRecord("invalid", nan, null);
        var infinityRecord = new VectorRecord("invalid", infinity, null);
        var zeroRecord = new VectorRecord("invalid", new float[dimensions], null);
        var emptyId = new VectorRecord("", queries[0], null);
        var overflow = new VectorRecord("invalid", queries[0], Map.of("key", "x".repeat(1025)));
        var invalidK = new SearchRequest("read_base", queries[0], 0, null, false, false);
        List<VectorRecord> emptyBatch = List.of();
        reject("dimension", "Collection-dependent dimension rejection on writer", ErrorCode.INVALID_ARGUMENT,
                () -> api.upsert("read_base", badDimension));
        reject("nan", "Shape validation rejects NaN before queueing", ErrorCode.INVALID_ARGUMENT,
                () -> api.upsert("read_base", nanRecord));
        reject("infinity", "Shape validation rejects infinity before queueing", ErrorCode.INVALID_ARGUMENT,
                () -> api.upsert("read_base", infinityRecord));
        reject("zero_cosine", "Metric validation rejects a zero cosine vector", ErrorCode.INVALID_ARGUMENT,
                () -> api.upsert("search_COSINE", zeroRecord));
        reject("empty_id", "Shape validation rejects empty ID", ErrorCode.INVALID_ARGUMENT,
                () -> api.upsert("read_base", emptyId));
        reject("metadata_overflow", "Shape validation rejects metadata value above default 1024-byte limit", ErrorCode.INVALID_ARGUMENT,
                () -> api.upsert("read_base", overflow));
        reject("empty_batch", "Shape validation rejects empty batch", ErrorCode.INVALID_ARGUMENT,
                () -> api.upsertBatch("read_base", emptyBatch));
        reject("invalid_k", "Search validation rejects zero k", ErrorCode.INVALID_ARGUMENT,
                () -> api.search(invalidK));
        reject("collection", "Read lookup rejects an absent collection", ErrorCode.NOT_FOUND,
                () -> api.getCollection("absent_collection"));
        var duplicateConfig = config("read_base", DistanceMetric.L2);
        reject("create", "Writer rejects duplicate collection creation", ErrorCode.ALREADY_EXISTS,
                () -> api.createCollection(duplicateConfig));
    }

    private void runMixed() throws Exception {
        System.out.println("Warmup and measurement: mixed workload (4 callers)");
        var search = searchWorkload("mixed.search", "mixed_base", false, 2);
        var get = new Workload("mixed.get", "Concurrent get reader; vector may be old or new committed version", "mixed_base", 1,
                vectors, vectors, 1,
                i -> { String id = records[i % vectors].id(); return () -> api.get("mixed_base", id); },
                (i, value) -> validateGet(value, records[i % vectors], false), null);
        var pool = updatePool();
        var update = new Workload("mixed.upsert", "Single updater contending with three readers; fixed cardinality", "mixed_base", 1,
                vectors, vectors, 1,
                i -> { var record = pool[i % POOL_SIZE]; return () -> api.upsert("mixed_base", record); },
                (i, value) -> require(!((UpsertResult) value).created(), "Mixed update inserted"), null);
        workloads.addAll(List.of(search, get, update));
        var ready = new CountDownLatch(4);
        var start = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var workerWorkloads = List.of(search, search, get, update);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            for (int worker = 0; worker < 4; worker++) {
                final int id = worker;
                var workload = workerWorkloads.get(worker);
                futures.add(executor.submit(() -> {
                    try {
                        for (int i = 0; i < warmup; i++) invoke(workload, id, i, false);
                    } catch (Throwable problem) {
                        failure.compareAndSet(null, problem);
                    } finally {
                        ready.countDown();
                    }
                    try {
                        start.await();
                        for (int i = 0; i < samples && failure.get() == null; i++) invoke(workload, id, i, true);
                    } catch (Throwable problem) {
                        failure.compareAndSet(null, problem);
                    }
                }));
            }
            ready.await();
            start.countDown();
            for (var future : futures) future.get();
        }
        if (failure.get() != null) throw new IllegalStateException("Mixed workload failed", failure.get());
        checkCardinality(search);
    }

    private Workload searchWorkload(String name, String collection, boolean fields, int threads) {
        var requests = new SearchRequest[POOL_SIZE];
        for (int i = 0; i < POOL_SIZE; i++) requests[i] = new SearchRequest(collection, queries[i], k, null, fields, fields);
        return new Workload(name, "Exact L2/IP/cosine search; includeMetadata=" + fields + "; includeVector=" + fields,
                collection, threads, vectors, vectors, 1,
                i -> { var request = requests[i % POOL_SIZE]; return () -> api.search(request); },
                (i, value) -> validateSearch(value, fields), null);
    }

    private void validateSearch(Object value, boolean fields) {
        var hits = (List<?>) value;
        require(hits.size() == k, "Unexpected search hit count");
        float previous = Float.NEGATIVE_INFINITY;
        var ids = new HashSet<String>();
        for (Object item : hits) {
            var hit = (SearchHit) item;
            require(Float.isFinite(hit.distance()) && hit.distance() >= previous, "Invalid or unordered search distance");
            require(ids.add(hit.id()), "Duplicate search ID");
            require(hit.id().startsWith("v"), "Unexpected search ID");
            if (fields) {
                require(hit.vector() != null && hit.vector().length == dimensions, "Missing search vector");
                require(hit.metadata() != null && hit.id().equals(hit.metadata().get("label")), "Missing search metadata");
            } else {
                require(hit.vector() == null && hit.metadata() == null, "Unrequested search fields returned");
            }
            previous = hit.distance();
        }
    }

    private void validateGet(Object value, VectorRecord expected, boolean compareVector) {
        var record = (VectorRecord) ((Optional<?>) value).orElseThrow();
        require(record.id().equals(expected.id()), "Wrong get ID");
        require(record.vector().length == dimensions, "Wrong get dimension");
        require(record.metadata().equals(expected.metadata()), "Wrong get metadata");
        if (compareVector) require(Arrays.equals(record.vector(), expected.vector()), "Wrong stored vector");
        for (float coordinate : record.vector()) require(Float.isFinite(coordinate), "Invalid stored coordinate");
    }

    private VectorRecord[] updatePool() {
        var pool = new VectorRecord[POOL_SIZE];
        for (int i = 0; i < pool.length; i++) {
            var source = records[i % vectors];
            pool[i] = new VectorRecord(source.id(), queries[i], source.metadata());
        }
        return pool;
    }

    private void reject(String suffix, String description, ErrorCode code, Supplier<?> call) {
        single(new Workload("reject." + code + "." + suffix, description, "read_base", 1, vectors, vectors, 1,
                i -> call, (i, value) -> {}, code));
    }

    private Workload ok(String name, String description, String collection, int batch,
                        IntFunction<Supplier<?>> prepare, BiConsumer<Integer, Object> validate) {
        return new Workload(name, description, collection, 1, vectors, vectors, batch, prepare, validate, null);
    }

    private void single(Workload workload) {
        workloads.add(workload);
        System.out.println("Warmup and measurement: " + workload.name());
        checkCardinality(workload);
        for (int i = 0; i < warmup; i++) invoke(workload, 0, i, false);
        for (int i = 0; i < samples; i++) invoke(workload, 0, i, true);
        checkCardinality(workload);
    }

    private void pair(Workload first, Workload second) {
        workloads.add(first);
        workloads.add(second);
        System.out.println("Warmup and measurement: " + first.name() + " + " + second.name());
        checkCardinality(first);
        for (int i = 0; i < warmup; i++) {
            invoke(first, 0, i, false);
            invoke(second, 0, i, false);
        }
        for (int i = 0; i < samples; i++) {
            invoke(first, 0, i, true);
            invoke(second, 0, i, true);
        }
        checkCardinality(first);
    }

    private void invoke(Workload workload, int worker, int sample, boolean measured) {
        Supplier<?> call = workload.prepare().apply(sample);
        Object value = null;
        Throwable thrown = null;
        long start = System.nanoTime();
        long elapsed;
        try {
            value = call.get();
            elapsed = System.nanoTime() - start;
        } catch (Throwable problem) {
            elapsed = System.nanoTime() - start;
            thrown = problem;
        }
        String outcome = "OK";
        try {
            if (thrown != null) {
                if (thrown instanceof VectorDbException db && db.getCode() == workload.expectedError()) {
                    outcome = db.getCode().name();
                } else {
                    outcome = "UNEXPECTED_" + (thrown instanceof VectorDbException db ? db.getCode().name() : thrown.getClass().getSimpleName());
                    throw new IllegalStateException("Unexpected API failure in " + workload.name(), thrown);
                }
            } else {
                if (workload.expectedError() != null) {
                    outcome = "UNEXPECTED_SUCCESS";
                    throw new IllegalStateException("Expected " + workload.expectedError() + " in " + workload.name());
                }
                try {
                    workload.validate().accept(sample, value);
                } catch (Throwable invalid) {
                    outcome = "UNEXPECTED_RESULT";
                    throw new IllegalStateException("Invalid API result in " + workload.name(), invalid);
                }
            }
        } finally {
            if (measured) rows.add(new Row(workload.name(), worker, sample, elapsed, outcome));
        }
    }

    private void checkCardinality(Workload workload) {
        if (workload.collection() != null) {
            require(api.getCollection(workload.collection()).vectorCount() == workload.finalVectors(),
                    "Cardinality changed for " + workload.name());
        }
    }

    private CollectionConfig config(String name, DistanceMetric metric) {
        return new CollectionConfig(name, dimensions, metric, HnswParams.defaults());
    }

    private float[] vector(Random random) {
        var result = new float[dimensions];
        for (int i = 0; i < result.length; i++) result[i] = random.nextFloat() * 2f - 1f;
        result[0] += result[0] >= 0 ? 0.01f : -0.01f;
        return result;
    }

    private void writeRows() throws IOException {
        try (var writer = Files.newBufferedWriter(output.resolve("latencies.csv"), StandardCharsets.UTF_8)) {
            writer.write("workload,phase,worker,sample,latency_ns,outcome\n");
            for (var row : rows) csv(writer, row.workload(), "measurement", row.worker(), row.sample(), row.nanos(), row.outcome());
        }
    }

    private void writeWorkloads() throws IOException {
        try (var writer = Files.newBufferedWriter(output.resolve("workloads.csv"), StandardCharsets.UTF_8)) {
            writer.write("workload,description,threads,initial_vectors,final_vectors,batch_size,warmup_per_worker,measured_per_worker,expected_outcome\n");
            for (var workload : workloads) csv(writer, workload.name(), workload.description(), workload.threads(),
                    workload.initialVectors(), workload.finalVectors(), workload.batchSize(), warmup, samples,
                    workload.expectedError() == null ? "OK" : workload.expectedError().name());
        }
    }

    private void writeMetadata() throws IOException {
        Files.writeString(output.resolve("metadata.json"), json(metadata) + "\n", StandardCharsets.UTF_8);
    }

    private static void csv(BufferedWriter writer, Object... values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) writer.write(',');
            writer.write('"');
            writer.write(values[i].toString().replace("\"", "\"\""));
            writer.write('"');
        }
        writer.write('\n');
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            var entries = new ArrayList<String>();
            map.forEach((key, item) -> entries.add(json(key.toString()) + ":" + json(item)));
            return "{" + String.join(",", entries) + "}";
        }
        if (value instanceof List<?> list) return "[" + String.join(",", list.stream().map(LatencyBenchmark::json).toList()) + "]";
        var escaped = new StringBuilder("\"");
        for (char ch : value.toString().toCharArray()) {
            switch (ch) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20 || Character.isSurrogate(ch)) escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    else escaped.append(ch);
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static int argument(String[] args, int index, int defaultValue) {
        return args.length > index ? Integer.parseInt(args[index]) : defaultValue;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
