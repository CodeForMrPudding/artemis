package io.artemis.math;

/** Computes a distance between equally sized, nonempty finite vectors. Smaller is closer. */
@FunctionalInterface
public interface DistanceFunction {
    float compute(float[] a, float[] b);
}
