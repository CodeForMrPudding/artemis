package io.artemis.api;

import io.artemis.api.error.ErrorCode;
import io.artemis.api.error.VectorDbException;
import io.artemis.api.model.*;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(startApplication = false)
class RequestValidatorTest {
    @Inject RequestValidator validator;

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void rejectsInvalidRequestsWithPublicErrorCode(String reason, Consumer<RequestValidator> action) {
        var error = assertThrows(VectorDbException.class, () -> action.accept(validator));
        assertEquals(ErrorCode.INVALID_ARGUMENT, error.getCode());
        assertFalse(error.getMessage().isBlank());
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                caseOf("null config", v -> v.validateCreateCollection(null)),
                caseOf("null collection", v -> v.validateCollectionName(null)),
                caseOf("empty collection", v -> v.validateCollectionName("")),
                caseOf("long collection", v -> v.validateCollectionName("a".repeat(65))),
                caseOf("collection space", v -> v.validateCollectionName("a b")),
                caseOf("collection unicode", v -> v.validateCollectionName("café")),
                caseOf("collection newline", v -> v.validateCollectionName("a\n")),
                caseOf("zero dimension", v -> v.validateCreateCollection(config(0, DistanceMetric.L2, HnswParams.defaults()))),
                caseOf("negative dimension", v -> v.validateCreateCollection(config(-1, DistanceMetric.L2, HnswParams.defaults()))),
                caseOf("oversize dimension", v -> v.validateCreateCollection(config(4097, DistanceMetric.L2, HnswParams.defaults()))),
                caseOf("null metric", v -> v.validateCreateCollection(config(3, null, HnswParams.defaults()))),
                caseOf("null hnsw", v -> v.validateCreateCollection(config(3, DistanceMetric.L2, null))),
                caseOf("zero m", v -> v.validateCreateCollection(config(3, DistanceMetric.L2, new HnswParams(0, 1, 1)))),
                caseOf("negative efConstruction", v -> v.validateCreateCollection(config(3, DistanceMetric.L2, new HnswParams(1, -1, 1)))),
                caseOf("zero efSearch", v -> v.validateCreateCollection(config(3, DistanceMetric.L2, new HnswParams(1, 1, 0)))),
                caseOf("null id", v -> v.validateId(null)),
                caseOf("empty id", v -> v.validateId("")),
                caseOf("oversize utf8 id", v -> v.validateId("é".repeat(129))),
                caseOf("malformed id", v -> v.validateId("\uD800")),
                caseOf("null batch", v -> v.validateUpsert("docs", null)),
                caseOf("empty batch", v -> v.validateUpsert("docs", List.of())),
                caseOf("oversize batch", v -> v.validateUpsert("docs", Collections.nCopies(1001, record(new float[]{1}, null)))),
                caseOf("bad upsert collection", v -> v.validateUpsert("?", List.of(record(new float[]{1}, null)))),
                caseOf("null record", v -> v.validateUpsert("docs", Arrays.asList((VectorRecord) null))),
                caseOf("bad record id", v -> v.validateUpsert("docs", List.of(new VectorRecord("", new float[]{1}, null)))),
                caseOf("null vector", v -> v.validateUpsert("docs", List.of(record(null, null)))),
                caseOf("empty vector", v -> v.validateUpsert("docs", List.of(record(new float[0], null)))),
                caseOf("oversize vector", v -> v.validateUpsert("docs", List.of(record(new float[4097], null)))),
                caseOf("NaN", v -> v.validateUpsert("docs", List.of(record(new float[]{Float.NaN}, null)))),
                caseOf("positive infinity", v -> v.validateUpsert("docs", List.of(record(new float[]{Float.POSITIVE_INFINITY}, null)))),
                caseOf("negative infinity", v -> v.validateUpsert("docs", List.of(record(new float[]{Float.NEGATIVE_INFINITY}, null)))),
                caseOf("too many metadata entries", v -> v.validateUpsert("docs", List.of(record(new float[]{1}, metadata(33))))),
                caseOf("metadata key utf8 limit", v -> v.validateUpsert("docs", List.of(record(new float[]{1}, Map.of("é".repeat(33), "v"))))),
                caseOf("metadata value utf8 limit", v -> v.validateUpsert("docs", List.of(record(new float[]{1}, Map.of("k", "é".repeat(513)))))),
                caseOf("null metadata key", v -> v.validateUpsert("docs", List.of(record(new float[]{1}, Collections.singletonMap(null, "v"))))),
                caseOf("null metadata value", v -> v.validateUpsert("docs", List.of(record(new float[]{1}, Collections.singletonMap("k", null))))),
                caseOf("malformed metadata", v -> v.validateUpsert("docs", List.of(record(new float[]{1}, Map.of("k", "\uDC00"))))),
                caseOf("null search", v -> v.validateSearch(null)),
                caseOf("bad search collection", v -> v.validateSearch(search("?", new float[]{1}, 1, null))),
                caseOf("null query", v -> v.validateSearch(search("docs", null, 1, null))),
                caseOf("empty query", v -> v.validateSearch(search("docs", new float[0], 1, null))),
                caseOf("oversize query", v -> v.validateSearch(search("docs", new float[4097], 1, null))),
                caseOf("NaN query", v -> v.validateSearch(search("docs", new float[]{Float.NaN}, 1, null))),
                caseOf("infinite query", v -> v.validateSearch(search("docs", new float[]{Float.POSITIVE_INFINITY}, 1, null))),
                caseOf("zero k", v -> v.validateSearch(search("docs", new float[]{1}, 0, null))),
                caseOf("negative k", v -> v.validateSearch(search("docs", new float[]{1}, -1, null))),
                caseOf("oversize k", v -> v.validateSearch(search("docs", new float[]{1}, 1001, null))),
                caseOf("zero search ef", v -> v.validateSearch(search("docs", new float[]{1}, 1, 0))),
                caseOf("negative search ef", v -> v.validateSearch(search("docs", new float[]{1}, 1, -1))),
                caseOf("wrong dimension", v -> v.validateDimension(new float[]{1, 2}, 3)),
                caseOf("null vector dimension", v -> v.validateDimension(null, 3)),
                caseOf("invalid expected dimension", v -> v.validateDimension(new float[0], 0)),
                caseOf("cosine zero", v -> v.validateVectorForMetric(new float[]{0f, -0f}, DistanceMetric.COSINE)),
                caseOf("metric helper null", v -> v.validateVectorForMetric(new float[]{1}, null)),
                caseOf("metric helper NaN", v -> v.validateVectorForMetric(new float[]{Float.NaN}, DistanceMetric.COSINE)),
                caseOf("invalid first duplicate is still checked", v -> v.validateUpsert("docs", List.of(
                        record(new float[]{Float.NaN}, null), record(new float[]{1}, null))))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validRequests")
    void acceptsValidBoundaries(String reason, Consumer<RequestValidator> action) {
        assertDoesNotThrow(() -> action.accept(validator));
    }

    static Stream<Arguments> validRequests() {
        return Stream.of(
                caseOf("one character name", v -> v.validateCollectionName("a")),
                caseOf("max name", v -> v.validateCollectionName("A_-09" + "x".repeat(59))),
                caseOf("minimum config", v -> v.validateCreateCollection(config(1, DistanceMetric.L2, new HnswParams(1, 1, 1)))),
                caseOf("max dimension", v -> v.validateCreateCollection(config(4096, DistanceMetric.COSINE, HnswParams.defaults()))),
                caseOf("UTF8 id at limit", v -> v.validateId("😀".repeat(64))),
                caseOf("single byte id", v -> v.validateId("x")),
                caseOf("metadata at byte limits", v -> v.validateUpsert("docs", List.of(record(new float[]{1}, Map.of("é".repeat(32), "😀".repeat(256)))))),
                caseOf("metadata at count limit", v -> v.validateUpsert("docs", List.of(record(new float[]{1}, metadata(32))))),
                caseOf("empty metadata text", v -> v.validateUpsert("docs", List.of(record(new float[]{1}, Map.of("", ""))))),
                caseOf("null metadata", v -> v.validateUpsert("docs", List.of(record(new float[]{1}, null)))),
                caseOf("max batch duplicate ids accepted", v -> v.validateUpsert("docs", Collections.nCopies(1000, record(new float[]{1}, null)))),
                caseOf("finite extreme floats", v -> v.validateUpsert("docs", List.of(record(new float[]{Float.MAX_VALUE, -Float.MAX_VALUE, Float.MIN_VALUE}, null)))),
                caseOf("search default ef and maximum k", v -> v.validateSearch(search("docs", new float[]{1}, 1000, null))),
                caseOf("ef below k is raised later", v -> v.validateSearch(search("docs", new float[]{1}, 10, 1))),
                caseOf("correct dimension", v -> v.validateDimension(new float[]{1, 2, 3}, 3)),
                caseOf("cosine subnormal nonzero", v -> v.validateVectorForMetric(new float[]{Float.MIN_VALUE, 0}, DistanceMetric.COSINE)),
                caseOf("L2 zero allowed", v -> v.validateVectorForMetric(new float[]{0}, DistanceMetric.L2)),
                caseOf("inner product zero allowed", v -> v.validateVectorForMetric(new float[]{0}, DistanceMetric.INNER_PRODUCT))
        );
    }

    @Test
    void validationDoesNotNormalizeOrMutateCallerData() {
        float[] vector = {3, 4};
        var metadata = new HashMap<>(Map.of("key", "value"));
        validator.validateUpsert("docs", List.of(record(vector, metadata)));
        validator.validateVectorForMetric(vector, DistanceMetric.COSINE);
        validator.validateSearch(search("docs", vector, 1, null));
        assertArrayEquals(new float[]{3, 4}, vector);
        assertEquals(Map.of("key", "value"), metadata);
    }

    @Test
    void invalidMetadataProducesTheSameOutcomeRegardlessOfMapIterationOrder() {
        var firstOrder = new LinkedHashMap<String, String>();
        firstOrder.put("z".repeat(65), "value");
        firstOrder.put("a", "v".repeat(1025));
        var reverseOrder = new LinkedHashMap<String, String>();
        reverseOrder.put("a", "v".repeat(1025));
        reverseOrder.put("z".repeat(65), "value");

        var first = assertThrows(VectorDbException.class,
                () -> validator.validateUpsert("docs", List.of(record(new float[]{1}, firstOrder))));
        var reverse = assertThrows(VectorDbException.class,
                () -> validator.validateUpsert("docs", List.of(record(new float[]{1}, reverseOrder))));
        assertEquals(first.getCode(), reverse.getCode());
        assertEquals(first.getMessage(), reverse.getMessage());
        assertEquals(List.of("z".repeat(65), "a"), new ArrayList<>(firstOrder.keySet()));
    }

    static CollectionConfig config(int dimension, DistanceMetric metric, HnswParams hnsw) {
        return new CollectionConfig("docs", dimension, metric, hnsw);
    }

    static VectorRecord record(float[] vector, Map<String, String> metadata) {
        return new VectorRecord("id", vector, metadata);
    }

    static SearchRequest search(String name, float[] query, int k, Integer ef) {
        return new SearchRequest(name, query, k, ef, false, false);
    }

    static Map<String, String> metadata(int count) {
        var map = new LinkedHashMap<String, String>();
        IntStream.range(0, count).forEach(i -> map.put("key" + i, "v"));
        return map;
    }

    private static Arguments caseOf(String name, Consumer<RequestValidator> action) {
        return Arguments.of(name, action);
    }
}
