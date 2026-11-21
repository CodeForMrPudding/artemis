package io.artemis.index;

import java.util.List;

/** Derived vector data; cosine vectors and queries are prepared by the collection service. */
public interface VectorIndex {
    void insert(long internalId, float[] vector);
    void markDeleted(long internalId);
    List<Neighbor> search(float[] query, int k, int efSearch);
    int size();

    record Neighbor(long internalId, float distance) {}
}
