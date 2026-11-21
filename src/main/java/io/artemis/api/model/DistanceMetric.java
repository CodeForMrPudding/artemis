package io.artemis.api.model;

/** All metrics return a distance where smaller means closer. */
public enum DistanceMetric {
    /** Squared Euclidean distance. */
    L2,
    /** Negated dot product. */
    INNER_PRODUCT,
    /** One minus cosine similarity. */
    COSINE
}
