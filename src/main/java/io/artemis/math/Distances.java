package io.artemis.math;

import io.artemis.api.model.DistanceMetric;

/** Primitive distance kernels. Cosine inputs must already be normalized. */
public final class Distances {
    private Distances() {}

    public static DistanceFunction forMetric(DistanceMetric metric) {
        if (metric == null) throw new IllegalArgumentException("metric is required");
        return (a, b) -> (float) computeWide(metric, a, b);
    }

    /** Retains wide scores for ranking even when the public float distance overflows or underflows. */
    public static double computeWide(DistanceMetric metric, float[] a, float[] b) {
        if (metric == null) throw new IllegalArgumentException("metric is required");
        return switch (metric) {
            case L2 -> squaredL2(a, b);
            case INNER_PRODUCT -> negativeDot(a, b);
            case COSINE -> 1 - Math.clamp(dot(a, b), -1, 1);
        };
    }

    /** Returns an owned unit vector, including for finite float extremes and subnormals. */
    public static float[] normalizedCopy(float[] input) {
        if (input == null || input.length == 0) {
            throw new IllegalArgumentException("vector must be nonempty");
        }
        float[] result = input.clone();
        double squaredNorm = 0;
        for (float value : result) {
            requireFinite(value);
            // Double covers the square of every finite float, including subnormals.
            squaredNorm += (double) value * value;
        }
        if (squaredNorm == 0) throw new IllegalArgumentException("cannot normalize a zero vector");
        double norm = Math.sqrt(squaredNorm);
        for (int i = 0; i < result.length; i++) result[i] = (float) (result[i] / norm);
        return result;
    }

    private static double squaredL2(float[] a, float[] b) {
        requireSameShape(a, b);
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            requireFinite(a[i]);
            requireFinite(b[i]);
            double delta = (double) a[i] - b[i];
            sum += delta * delta;
        }
        return sum;
    }

    private static double negativeDot(float[] a, float[] b) {
        double result = -dot(a, b);
        return result == 0 ? 0 : result;
    }

    private static double dot(float[] a, float[] b) {
        requireSameShape(a, b);
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            requireFinite(a[i]);
            requireFinite(b[i]);
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    private static void requireSameShape(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            throw new IllegalArgumentException("vectors must have equal positive dimensions");
        }
    }

    private static void requireFinite(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("vector values must be finite");
    }
}
