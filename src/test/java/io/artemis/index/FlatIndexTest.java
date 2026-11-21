package io.artemis.index;

import io.artemis.api.model.DistanceMetric;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FlatIndexTest {
    @Test
    void exactTopKSortsByDistanceThenAscendingInternalId() {
        VectorIndex index = new FlatIndex(2, DistanceMetric.L2);
        index.insert(9, new float[]{1, 0});
        index.insert(4, new float[]{-1, 0});
        index.insert(8, new float[]{0, 0});
        index.insert(1, new float[]{5, 5});
        assertEquals(List.of(new VectorIndex.Neighbor(8, 0), new VectorIndex.Neighbor(4, 1)),
                index.search(new float[]{0, 0}, 2, 1));
        assertEquals(List.of(new VectorIndex.Neighbor(8, 0), new VectorIndex.Neighbor(4, 1),
                        new VectorIndex.Neighbor(9, 1), new VectorIndex.Neighbor(1, 50)),
                index.search(new float[]{0, 0}, 10, 10));
        assertEquals(4, index.size());
    }

    @Test
    void insertsOwnTheirVectorsAndSearchLeavesQueryUntouched() {
        VectorIndex index = new FlatIndex(2, DistanceMetric.L2);
        float[] vector = {3, 4};
        index.insert(0, vector);
        vector[0] = 100;
        float[] query = {0, 0};
        assertEquals(List.of(new VectorIndex.Neighbor(0, 25)), index.search(query, 1, 1));
        assertArrayEquals(new float[]{0, 0}, query);
    }

    @Test
    void deletionIsIdempotentAndRemovesHitsAndLiveCount() {
        VectorIndex index = new FlatIndex(1, DistanceMetric.L2);
        assertTrue(index.search(new float[]{0}, 3, 3).isEmpty());
        index.insert(0, new float[]{0});
        index.insert(1, new float[]{1});
        index.markDeleted(0);
        index.markDeleted(0);
        index.markDeleted(999);
        assertEquals(1, index.size());
        assertEquals(List.of(new VectorIndex.Neighbor(1, 1)), index.search(new float[]{0}, 3, 3));
        index.markDeleted(1);
        assertEquals(0, index.size());
        assertTrue(index.search(new float[]{0}, 1, 1).isEmpty());
    }

    @Test
    void normalizedCosineAndInnerProductProduceExpectedOrdering() {
        VectorIndex cosine = new FlatIndex(2, DistanceMetric.COSINE);
        cosine.insert(0, new float[]{0, 1});
        cosine.insert(1, new float[]{-1, 0});
        cosine.insert(2, new float[]{1, 0});
        assertEquals(List.of(new VectorIndex.Neighbor(2, 0), new VectorIndex.Neighbor(0, 1),
                        new VectorIndex.Neighbor(1, 2)), cosine.search(new float[]{1, 0}, 3, 3));
        VectorIndex inner = new FlatIndex(1, DistanceMetric.INNER_PRODUCT);
        inner.insert(4, new float[]{-3});
        inner.insert(5, new float[]{7});
        assertEquals(List.of(new VectorIndex.Neighbor(5, -14), new VectorIndex.Neighbor(4, 6)),
                inner.search(new float[]{2}, 2, 2));
    }

    @Test
    void finiteExtremeDistancesRemainSortableAndNeverNaN() {
        VectorIndex index = new FlatIndex(1, DistanceMetric.L2);
        index.insert(Long.MAX_VALUE, new float[]{Float.MAX_VALUE});
        index.insert(0, new float[]{-Float.MAX_VALUE});
        index.insert(2, new float[]{0});
        assertEquals(List.of(new VectorIndex.Neighbor(2, 0), new VectorIndex.Neighbor(0, Float.POSITIVE_INFINITY),
                        new VectorIndex.Neighbor(Long.MAX_VALUE, Float.POSITIVE_INFINITY)),
                index.search(new float[]{0}, Integer.MAX_VALUE, 1));
    }

    @Test
    void squaredL2RanksOverflowingScoresBeforeConvertingPublicDistancesToFloat() {
        VectorIndex index = new FlatIndex(1, DistanceMetric.L2);
        index.insert(0, new float[]{2e20f});
        index.insert(1, new float[]{1e20f});
        assertEquals(List.of(new VectorIndex.Neighbor(1, Float.POSITIVE_INFINITY)),
                index.search(new float[]{0}, 1, 1));
        assertEquals(List.of(new VectorIndex.Neighbor(1, Float.POSITIVE_INFINITY),
                        new VectorIndex.Neighbor(0, Float.POSITIVE_INFINITY)),
                index.search(new float[]{0}, 2, 2));
    }

    @Test
    void squaredL2RanksSubnormalScoresThatRoundToTheSameFloat() {
        VectorIndex index = new FlatIndex(1, DistanceMetric.L2);
        index.insert(0, new float[]{2 * Float.MIN_VALUE});
        index.insert(1, new float[]{Float.MIN_VALUE});
        assertEquals(List.of(new VectorIndex.Neighbor(1, 0)), index.search(new float[]{0}, 1, 1));
        assertEquals(List.of(new VectorIndex.Neighbor(1, 0), new VectorIndex.Neighbor(0, 0)),
                index.search(new float[]{0}, 2, 2));
    }

    @Test
    void innerProductRanksOverflowingScoresBeforeConvertingPublicDistancesToFloat() {
        VectorIndex index = new FlatIndex(1, DistanceMetric.INNER_PRODUCT);
        index.insert(0, new float[]{1e20f});
        index.insert(1, new float[]{2e20f});
        assertEquals(List.of(new VectorIndex.Neighbor(1, Float.NEGATIVE_INFINITY)),
                index.search(new float[]{1e20f}, 1, 1));
        assertEquals(List.of(new VectorIndex.Neighbor(1, Float.NEGATIVE_INFINITY),
                        new VectorIndex.Neighbor(0, Float.NEGATIVE_INFINITY)),
                index.search(new float[]{1e20f}, 2, 2));
    }

    @Test
    void concurrentSearchAndMutationKeepOwnedArraysAndLiveResultsConsistent() throws Exception {
        VectorIndex index = new FlatIndex(1, DistanceMetric.L2);
        index.insert(0, new float[]{0});
        var executor = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int reader = 0; reader < 4; reader++) {
                tasks.add(executor.submit(() -> {
                    start.await();
                    for (int iteration = 0; iteration < 150; iteration++) {
                        List<VectorIndex.Neighbor> hits = index.search(new float[]{0}, 5, 5);
                        assertEquals(new VectorIndex.Neighbor(0, 0), hits.getFirst());
                        for (int hit = 1; hit < hits.size(); hit++) {
                            assertTrue(hits.get(hit - 1).distance() <= hits.get(hit).distance());
                        }
                    }
                    return null;
                }));
            }
            tasks.add(executor.submit(() -> {
                start.await();
                for (int id = 1; id <= 100; id++) {
                    index.insert(id, new float[]{id});
                    if (id % 2 == 0) index.markDeleted(id);
                }
                return null;
            }));
            start.countDown();
            for (Future<?> task : tasks) task.get(10, TimeUnit.SECONDS);
            assertEquals(51, index.size());
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @EnumSource(DistanceMetric.class)
    void rejectsMalformedInputsWithoutChangingIndex(DistanceMetric metric) {
        VectorIndex index = new FlatIndex(2, metric);
        for (float[] invalid : new float[][]{null, {}, {1}, {1, 2, 3}, {Float.NaN, 0}, {0, Float.NEGATIVE_INFINITY}}) {
            assertThrows(IllegalArgumentException.class, () -> index.insert(0, invalid));
            assertThrows(IllegalArgumentException.class, () -> index.search(invalid, 1, 1));
        }
        assertThrows(IllegalArgumentException.class, () -> index.insert(-1, new float[]{1, 0}));
        assertThrows(IllegalArgumentException.class, () -> index.markDeleted(-1));
        assertThrows(IllegalArgumentException.class, () -> index.search(new float[]{1, 0}, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> index.search(new float[]{1, 0}, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> index.search(new float[]{1, 0}, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> index.search(new float[]{1, 0}, 1, -1));
        assertEquals(0, index.size());
        index.insert(0, new float[]{1, 0});
        assertThrows(IllegalArgumentException.class, () -> index.insert(0, new float[]{0, 1}));
        assertEquals(List.of(new VectorIndex.Neighbor(0, metric == DistanceMetric.INNER_PRODUCT ? -1 : 0)),
                index.search(new float[]{1, 0}, 1, 1));
    }

    @Test
    void rejectsInvalidConfigurationAndZeroCosineVectors() {
        assertThrows(IllegalArgumentException.class, () -> new FlatIndex(0, DistanceMetric.L2));
        assertThrows(IllegalArgumentException.class, () -> new FlatIndex(-1, DistanceMetric.L2));
        assertThrows(IllegalArgumentException.class, () -> new FlatIndex(1, null));
        VectorIndex cosine = new FlatIndex(2, DistanceMetric.COSINE);
        assertThrows(IllegalArgumentException.class, () -> cosine.insert(0, new float[]{0, -0f}));
        assertThrows(IllegalArgumentException.class, () -> cosine.search(new float[]{0, 0}, 1, 1));
    }
}
