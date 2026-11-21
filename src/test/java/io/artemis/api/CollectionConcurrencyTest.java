package io.artemis.api;

import io.artemis.api.error.*;
import io.artemis.api.model.*;
import io.artemis.command.*;
import io.artemis.config.*;
import io.artemis.storage.InMemoryStorageEngine;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static io.artemis.api.CollectionServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

class CollectionConcurrencyTest {
    static final class Harness implements AutoCloseable {
        final ApplicationContext context = ApplicationContext.run(Map.of("vectordb.enabled", false));
        final GatedStorageEngine storage = new GatedStorageEngine(new InMemoryStorageEngine());
        final AtomicReference<Duration> timeout = new AtomicReference<>(Duration.ofSeconds(5));
        final WriterConfig writer = new WriterConfig() {
            public int getQueueCapacity() { return 1; }
            public boolean isSyncWrites() { return true; }
            public Duration getRequestTimeout() { return timeout.get(); }
        };
        final EngineLifecycle lifecycle = new EngineLifecycle(storage, context.getBean(RequestValidator.class), writer);
        final CollectionService service = new CollectionServiceImpl(lifecycle, context.getBean(RequestValidator.class), lifecycle.applier(), lifecycle.registry(), writer);
        Harness() { lifecycle.start(); service.createCollection(config("c", DistanceMetric.L2)); }
        public void close() { lifecycle.close(); context.close(); }
    }

    @Test
    void idleCollectionsDoNotRetainDatabaseSnapshots() {
        try (var h = new Harness()) {
            h.service.createCollection(config("cold", DistanceMetric.L2));
            h.service.upsert("c", row("x", 1, 0));
            h.service.search(query("c", new float[]{1, 0}, 1, true));
            assertEquals(0, h.storage.openSnapshots());
        }
    }

    @Test
    void readsDoNotWaitForTheNextCommitAndSeeItAfterAcknowledgment() throws Exception {
        try (var h = new Harness(); var gate = h.storage.pauseCommit()) {
            var write = CompletableFuture.supplyAsync(() -> h.service.upsert("c", row("x", 1, 0)));
            gate.awaitEntry();
            var read = CompletableFuture.supplyAsync(() -> h.service.search(query("c", new float[]{1, 0}, 1, true)));
            assertTrue(read.get(1, TimeUnit.SECONDS).isEmpty());
            assertTrue(h.service.get("c", "x").isEmpty());
            gate.close();
            write.get(5, TimeUnit.SECONDS);
            assertEquals("x", h.service.search(query("c", new float[]{1, 0}, 1, true)).getFirst().id());
        }
    }

    @Test
    void fullQueueFailsFastAndShutdownDrainsAllAcceptedWrites() throws Exception {
        try (var h = new Harness(); var gate = h.storage.pauseCommit()) {
            var applier = h.lifecycle.applier();
            var first = applier.submit(new UpsertBatchCmd("c", List.of(row("x", 1, 0))));
            gate.awaitEntry();
            var second = applier.submit(new UpsertBatchCmd("c", List.of(row("y", 0, 1))));
            code(ErrorCode.RESOURCE_EXHAUSTED, () -> applier.submit(new DeleteCmd("c", "x")));
            var closing = CompletableFuture.runAsync(h.lifecycle::close);
            await(() -> h.lifecycle.state() == EngineLifecycle.State.CLOSING);
            code(ErrorCode.UNAVAILABLE, () -> applier.submit(new DeleteCmd("c", "x")));
            assertFalse(closing.isDone());
            gate.close();
            assertInstanceOf(Applied.class, first.get(5, TimeUnit.SECONDS));
            assertInstanceOf(Applied.class, second.get(5, TimeUnit.SECONDS));
            closing.get(5, TimeUnit.SECONDS);
            assertEquals(3, applier.lastAppliedSeq());
        }
    }

    @Test
    void timedOutWaitDoesNotCancelTheAcceptedCommand() throws Exception {
        try (var h = new Harness(); var gate = h.storage.pauseCommit()) {
            h.timeout.set(Duration.ofMillis(30));
            var write = CompletableFuture.runAsync(() -> code(ErrorCode.UNAVAILABLE, () -> h.service.upsert("c", row("x", 1, 0))));
            gate.awaitEntry();
            write.get(5, TimeUnit.SECONDS);
            gate.close();
            h.timeout.set(Duration.ofSeconds(5));
            h.lifecycle.applier().submit(new DeleteCmd("c", "absent")).get(5, TimeUnit.SECONDS);
            assertTrue(h.service.get("c", "x").isPresent());
        }
    }

    @Test
    void storageFailureStopsAdmissionAndDoesNotPublishPartialState() {
        try (var h = new Harness()) {
            h.storage.failNextCommit(new VectorDbException(ErrorCode.INTERNAL, "injected storage failure"));
            code(ErrorCode.INTERNAL, () -> h.service.upsert("c", row("x", 1, 0)));
            assertEquals(EngineLifecycle.State.CLOSING, h.lifecycle.state());
            code(ErrorCode.UNAVAILABLE, h.service::listCollections);
            code(ErrorCode.UNAVAILABLE, () -> h.lifecycle.applier().submit(new DeleteCmd("c", "x")));
            assertEquals(1, h.lifecycle.applier().lastAppliedSeq());
            assertEquals(0, h.storage.openSnapshots());
        }
    }

    @Test
    void interruptedWaitPreservesInterruptAndDoesNotCancelAcceptedWrite() throws Exception {
        try (var h = new Harness(); var gate = h.storage.pauseCommit()) {
            var interrupted = new CompletableFuture<Boolean>();
            var writer = new Thread(() -> {
                try { h.service.upsert("c", row("x", 1, 0)); interrupted.complete(false); }
                catch (VectorDbException e) { interrupted.complete(e.getCode() == ErrorCode.UNAVAILABLE && Thread.currentThread().isInterrupted()); }
            });
            writer.start(); gate.awaitEntry(); writer.interrupt();
            assertTrue(interrupted.get(5, TimeUnit.SECONDS));
            gate.close();
            h.lifecycle.applier().submit(new DeleteCmd("c", "absent")).get(5, TimeUnit.SECONDS);
            assertTrue(h.service.get("c", "x").isPresent());
            writer.join(5000);
        }
    }

    @Test
    void inFlightSearchFinishesAcrossDropWhileNewSearchesFail() throws Exception {
        try (var h = new Harness()) {
            h.service.upsert("c", row("x", 1, 0));
            try (var gate = h.storage.pauseSearch()) {
                var search = CompletableFuture.supplyAsync(() -> h.service.search(query("c", new float[]{1, 0}, 1, true)));
                gate.awaitEntry();
                var drop = CompletableFuture.runAsync(() -> h.service.dropCollection("c"));
                await(() -> !h.lifecycle.registry().contains("c"));
                code(ErrorCode.NOT_FOUND, () -> h.service.search(query("c", new float[]{1, 0}, 1, true)));
                gate.close();
                assertEquals("x", search.get(5, TimeUnit.SECONDS).getFirst().id());
                drop.get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("Condition did not become true");
            Thread.onSpinWait();
        }
    }
}
