package io.artemis.api.model;

import lombok.Builder;

/** Dimension and metric are fixed for the lifetime of a collection. */
@Builder
public record CollectionConfig(String name, int dimension, DistanceMetric metric, HnswParams hnsw) {}
