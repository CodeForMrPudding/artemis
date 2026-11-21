package io.artemis.math;

import io.artemis.api.model.DistanceMetric;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DistancesTest {
    @Test
    void metricsAllUseSmallerIsCloser() {
        float[] a = {1, 2};
        float[] b = {4, 6};
        assertEquals(25f, Distances.forMetric(DistanceMetric.L2).compute(a, b));
        assertEquals(-16f, Distances.forMetric(DistanceMetric.INNER_PRODUCT).compute(a, b));
        DistanceFunction cosine = Distances.forMetric(DistanceMetric.COSINE);
        assertEquals(0f, cosine.compute(new float[]{1, 0}, new float[]{1, 0}));
        assertEquals(1f, cosine.compute(new float[]{1, 0}, new float[]{0, 1}));
        assertEquals(2f, cosine.compute(new float[]{1, 0}, new float[]{-1, 0}));
    }

    @Test
    void finiteExtremeInputsOverflowToInfinityWithoutNaN() {
        float max = Float.MAX_VALUE;
        assertEquals(Float.POSITIVE_INFINITY,
                Distances.forMetric(DistanceMetric.L2).compute(new float[]{max}, new float[]{-max}));
        assertEquals(0f, Distances.forMetric(DistanceMetric.INNER_PRODUCT)
                .compute(new float[]{max, max}, new float[]{max, -max}));
    }

    @Test
    void normalizationReturnsAnOwnedUnitVectorWithoutChangingInput() {
        float[] input = {3, 4};
        float[] normalized = Distances.normalizedCopy(input);
        assertNotSame(input, normalized);
        assertArrayEquals(new float[]{3, 4}, input);
        assertArrayEquals(new float[]{0.6f, 0.8f}, normalized, 1e-7f);
    }

    @Test
    void cosineClampsFloatNormalizationRoundingToValidDistanceRange() {
        float[] vector = Distances.normalizedCopy(new float[]{3, 4});
        float[] opposite = {-vector[0], -vector[1]};
        DistanceFunction cosine = Distances.forMetric(DistanceMetric.COSINE);
        assertEquals(0f, cosine.compute(vector, vector));
        assertEquals(2f, cosine.compute(vector, opposite));
    }

    @ParameterizedTest
    @ValueSource(floats = {Float.MAX_VALUE, Float.MIN_VALUE, Float.MIN_NORMAL, -Float.MAX_VALUE, -Float.MIN_VALUE})
    void normalizationHandlesVeryLargeAndSubnormalInputs(float magnitude) {
        float[] normalized = Distances.normalizedCopy(new float[]{magnitude, magnitude});
        float component = Math.copySign((float) Math.sqrt(0.5), magnitude);
        assertArrayEquals(new float[]{component, component}, normalized, 1e-7f);
        assertEquals(1.0, (double) normalized[0] * normalized[0] + (double) normalized[1] * normalized[1], 1e-6);
    }

    @Test
    void normalizationHandlesMixedScalesAndRejectsZeroOrMalformedVectors() {
        assertArrayEquals(new float[]{1, 0}, Distances.normalizedCopy(new float[]{Float.MAX_VALUE, Float.MIN_VALUE}));
        for (float[] invalid : new float[][]{null, {}, {0, -0f}, {Float.NaN}, {Float.POSITIVE_INFINITY}, {Float.NEGATIVE_INFINITY}}) {
            assertThrows(IllegalArgumentException.class, () -> Distances.normalizedCopy(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> Distances.forMetric(null));
    }

    @ParameterizedTest
    @EnumSource(DistanceMetric.class)
    void kernelsRejectMalformedVectorsAndLeaveInputsUntouched(DistanceMetric metric) {
        DistanceFunction distance = Distances.forMetric(metric);
        for (float[] invalid : new float[][]{null, {}, {1}, {Float.NaN, 0}, {0, Float.POSITIVE_INFINITY}}) {
            assertThrows(IllegalArgumentException.class, () -> distance.compute(new float[]{1, 0}, invalid));
            assertThrows(IllegalArgumentException.class, () -> distance.compute(invalid, new float[]{1, 0}));
        }
        float[] a = {1, 0};
        float[] b = {0, 1};
        distance.compute(a, b);
        assertArrayEquals(new float[]{1, 0}, a);
        assertArrayEquals(new float[]{0, 1}, b);
    }
}
