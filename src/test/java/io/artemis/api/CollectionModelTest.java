package io.artemis.api;

import io.artemis.api.model.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import static io.artemis.api.CollectionServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

class CollectionModelTest {
    private record Expected(float[] vector, long internalId) {}

    @ParameterizedTest
    @EnumSource(DistanceMetric.class)
    void seededMutationSequencesMatchMapAndBruteForceSearch(DistanceMetric metric) {
        var fixture = new CollectionServiceTest();
        fixture.setup();
        try {
            var service = fixture.service;
            service.createCollection(config("c", metric));
            var model = new HashMap<String, Expected>();
            var random = new Random(42117);
            long internal = 0;
            for (int step = 0; step < 120; step++) {
                String id = "id" + random.nextInt(12);
                if (random.nextInt(4) == 0) {
                    assertEquals(model.remove(id) != null, service.delete("c", id).existed());
                } else {
                    float[] vector = {random.nextInt(9) + 1, random.nextInt(9) - 4};
                    boolean expectedCreated = !model.containsKey(id);
                    assertEquals(expectedCreated, service.upsert("c", new VectorRecord(id, vector, null)).created());
                    if (metric == DistanceMetric.COSINE) {
                        double norm = Math.sqrt((double) vector[0] * vector[0] + (double) vector[1] * vector[1]);
                        vector = new float[]{(float) (vector[0] / norm), (float) (vector[1] / norm)};
                    }
                    model.put(id, new Expected(vector, internal++));
                }
                assertEquals(model.size(), service.getCollection("c").vectorCount());
                float[] query = {1, 2};
                var expected = new ArrayList<>(model.entrySet());
                expected.sort(Comparator.<Map.Entry<String, Expected>>comparingDouble(e -> score(metric, query, e.getValue().vector()))
                        .thenComparingLong(e -> e.getValue().internalId()));
                var actual = service.search(query("c", query, 5, true));
                assertEquals(expected.stream().limit(5).map(Map.Entry::getKey).toList(), actual.stream().map(SearchHit::id).toList());
                for (int i = 0; i < actual.size(); i++) {
                    assertEquals((float) score(metric, query, expected.get(i).getValue().vector()), actual.get(i).distance(), 1e-6f);
                    assertArrayEquals(expected.get(i).getValue().vector(), actual.get(i).vector());
                }
            }
        } finally { fixture.close(); }
    }

    private static double score(DistanceMetric metric, float[] query, float[] vector) {
        if (metric == DistanceMetric.L2) {
            double x = (double) query[0] - vector[0], y = (double) query[1] - vector[1];
            return x * x + y * y;
        }
        double a = query[0], b = query[1];
        if (metric == DistanceMetric.COSINE) {
            double norm = Math.sqrt(a * a + b * b);
            a = (float) (a / norm); b = (float) (b / norm);
        }
        double dot = a * vector[0] + b * vector[1];
        return metric == DistanceMetric.INNER_PRODUCT ? -dot : 1 - Math.max(-1, Math.min(1, dot));
    }
}
