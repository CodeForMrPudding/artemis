package io.artemis.catalog;

import io.artemis.api.error.*;
import io.artemis.api.model.*;
import io.artemis.index.*;
import io.artemis.storage.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CollectionStateTest {
    private static CollectionConfig config(String name) { return new CollectionConfig(name, 2, DistanceMetric.L2, HnswParams.defaults()); }

    @Test
    void failedPublicationCannotExposeThePartiallyChangedIndex() {
        try (var engine = new InMemoryStorageEngine()) {
            var delegate = new FlatIndex(2, DistanceMetric.L2);
            delegate.insert(0, new float[]{1, 0});
            VectorIndex failing = new VectorIndex() {
                public void insert(long id, float[] vector) { throw new IllegalStateException("injected index failure"); }
                public void markDeleted(long id) { delegate.markDeleted(id); }
                public List<Neighbor> search(float[] q, int k, int ef) { return delegate.search(q, k, ef); }
                public int size() { return delegate.size(); }
            };
            try (var state = new CollectionState(0, config("a"), 1, 1, failing, engine)) {
                state.freezeForCommit();
                assertThrows(IllegalStateException.class,
                        () -> state.publish(2, 1, List.of(0L), List.of(new CollectionState.Insertion(1, new float[]{0, 1}))));
                var error = assertThrows(VectorDbException.class, () -> { try (var ignored = state.read()) { } });
                assertEquals(ErrorCode.UNAVAILABLE, error.getCode());
            }
        }
    }

    @Test
    void publicationContentionDoesNotBlockOtherCollectionsRegistryLookups() throws Exception {
        try (var engine = new InMemoryStorageEngine(); var registry = new CollectionRegistry()) {
            var a = new CollectionState(0, config("a"), 0, 0, new FlatIndex(2, DistanceMetric.L2), engine);
            registry.add(a);
            registry.add(new CollectionState(1, config("b"), 0, 0, new FlatIndex(2, DistanceMetric.L2), engine));
            var held = registry.read("a");
            a.freezeForCommit();
            var publish = new Thread(() -> a.publish(0, 0, List.of(), List.of()));
            var readA = new Thread(() -> { try (var lease = registry.read("a")) { lease.info(); } });
            try {
                publish.start(); awaitParked(publish);
                readA.start(); awaitParked(readA);
                var readB = CompletableFuture.supplyAsync(() -> { try (var lease = registry.read("b")) { return lease.info(); } });
                assertEquals("b", readB.get(1, TimeUnit.SECONDS).config().name());
            } finally {
                held.close(); publish.join(5000); readA.join(5000);
            }
        }
    }

    private static void awaitParked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.WAITING) {
            if (!thread.isAlive() || System.nanoTime() > deadline) fail("Thread did not reach its lock wait");
            Thread.onSpinWait();
        }
    }
}
