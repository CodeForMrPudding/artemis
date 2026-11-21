package io.artemis.config;

import io.artemis.api.RequestValidator;
import io.artemis.api.error.ErrorCode;
import io.artemis.api.error.VectorDbException;
import io.artemis.api.model.*;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class RequestValidatorConfigurationTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("overrides")
    void bindsAndEnforcesEveryConfiguredLimit(String property, Consumer<RequestValidator> action) {
        try (var defaults = ApplicationContext.run()) {
            assertDoesNotThrow(() -> action.accept(defaults.getBean(RequestValidator.class)));
        }
        try (var context = ApplicationContext.run(Map.of("vectordb.limits." + property, 1))) {
            var error = assertThrows(VectorDbException.class,
                    () -> action.accept(context.getBean(RequestValidator.class)));
            assertEquals(ErrorCode.INVALID_ARGUMENT, error.getCode());
        }
    }

    static Stream<Arguments> overrides() {
        return Stream.of(
                arg("max-collection-name-length", v -> v.validateCollectionName("ab")),
                arg("max-dimension", v -> v.validateCreateCollection(new CollectionConfig("a", 2, DistanceMetric.L2, HnswParams.defaults()))),
                arg("max-id-bytes", v -> v.validateId("é")),
                arg("max-batch-size", v -> v.validateUpsert("a", List.of(row(Map.of()), row(Map.of())))),
                arg("max-k", v -> v.validateSearch(new SearchRequest("a", new float[]{1}, 2, null, false, false))),
                arg("max-metadata-entries", v -> v.validateUpsert("a", List.of(row(Map.of("a", "b", "c", "d"))))),
                arg("max-metadata-key-bytes", v -> v.validateUpsert("a", List.of(row(Map.of("é", "a"))))),
                arg("max-metadata-value-bytes", v -> v.validateUpsert("a", List.of(row(Map.of("a", "é")))))
        );
    }

    private static VectorRecord row(Map<String, String> metadata) {
        return new VectorRecord("a", new float[]{1}, metadata);
    }

    private static Arguments arg(String property, Consumer<RequestValidator> action) {
        return Arguments.of(property, action);
    }
}
