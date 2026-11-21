package io.artemis.index;

import io.artemis.api.model.DistanceMetric;
import io.artemis.math.Distances;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Exact top-k search over owned vectors, with logical deletion. Searches share a read lock;
 * insertions and deletions hold its write lock.
 * Cosine vectors and queries must already be normalized; recovery preserves stored values exactly.
 */
public final class FlatIndex implements VectorIndex {
    private static final Comparator<Candidate> NEAREST_FIRST = Comparator
            .comparingDouble(Candidate::score).thenComparingLong(Candidate::internalId);

    private final int dimension;
    private final DistanceMetric metric;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private long[] ids = new long[16];
    private float[][] vectors = new float[16][];
    private boolean[] deleted = new boolean[16];
    private int entries;
    private int liveCount;

    public FlatIndex(int dimension, DistanceMetric metric) {
        if (dimension <= 0) throw new IllegalArgumentException("dimension must be positive");
        if (metric == null) throw new IllegalArgumentException("metric is required");
        this.dimension = dimension;
        this.metric = metric;
    }

    @Override
    public void insert(long internalId, float[] vector) {
        requireId(internalId);
        if (vector == null) throw new IllegalArgumentException("vector is required");
        float[] owned = vector.clone();
        validateVector(owned);
        lock.writeLock().lock();
        try {
            for (int i = 0; i < entries; i++) {
                if (ids[i] == internalId && !deleted[i]) {
                    throw new IllegalArgumentException("internal ID is already live");
                }
            }
            if (entries == ids.length) {
                int capacity = Math.addExact(entries, Math.max(1, entries / 2));
                ids = Arrays.copyOf(ids, capacity);
                vectors = Arrays.copyOf(vectors, capacity);
                deleted = Arrays.copyOf(deleted, capacity);
            }
            ids[entries] = internalId;
            vectors[entries] = owned;
            entries++;
            liveCount++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void markDeleted(long internalId) {
        requireId(internalId);
        lock.writeLock().lock();
        try {
            for (int i = 0; i < entries; i++) {
                if (ids[i] == internalId && !deleted[i]) {
                    deleted[i] = true;
                    vectors[i] = null;
                    liveCount--;
                    return;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<Neighbor> search(float[] query, int k, int efSearch) {
        validateVector(query);
        if (k <= 0 || efSearch <= 0) throw new IllegalArgumentException("k and efSearch must be positive");
        lock.readLock().lock();
        try {
            int limit = Math.min(k, liveCount);
            if (limit == 0) return List.of();
            // Exact scanning considers every vector, exceeding even max(k, efSearch).
            PriorityQueue<Candidate> nearest = new PriorityQueue<>(limit, NEAREST_FIRST.reversed());
            for (int i = 0; i < entries; i++) {
                if (deleted[i]) continue;
                Candidate candidate = new Candidate(ids[i], Distances.computeWide(metric, query, vectors[i]));
                if (nearest.size() < limit) {
                    nearest.add(candidate);
                } else if (NEAREST_FIRST.compare(candidate, nearest.peek()) < 0) {
                    nearest.poll();
                    nearest.add(candidate);
                }
            }
            List<Candidate> ranked = new ArrayList<>(nearest);
            ranked.sort(NEAREST_FIRST);
            List<Neighbor> result = new ArrayList<>(ranked.size());
            for (Candidate candidate : ranked) result.add(new Neighbor(candidate.internalId(), (float) candidate.score()));
            return List.copyOf(result);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try {
            return liveCount;
        } finally {
            lock.readLock().unlock();
        }
    }

    private void validateVector(float[] vector) {
        if (vector == null || vector.length != dimension) {
            throw new IllegalArgumentException("vector dimension does not match index");
        }
        boolean nonzero = false;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("vector values must be finite");
            nonzero |= value != 0;
        }
        if (metric == DistanceMetric.COSINE && !nonzero) {
            throw new IllegalArgumentException("cosine vector must be nonzero");
        }
    }

    private static void requireId(long internalId) {
        if (internalId < 0) throw new IllegalArgumentException("internal ID must be nonnegative");
    }

    private record Candidate(long internalId, double score) {}
}
