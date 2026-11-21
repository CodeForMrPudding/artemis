package io.artemis.command;

import io.artemis.api.RequestValidator;
import io.artemis.api.error.*;
import io.artemis.catalog.CollectionRegistry;
import io.artemis.config.WriterConfig;
import io.artemis.storage.StorageEngine;
import java.util.concurrent.*;

/** A bounded queue feeds one deterministic apply path; explicit replay uses the same lock. */
public final class SingleWriterCommandApplier implements CommandApplier, AutoCloseable {
    private final Object admission = new Object();
    private final Object applyLock = new Object();
    private final CommandProcessor processor;
    private final ThreadPoolExecutor executor;
    private final Runnable onFailure;
    private volatile boolean started;
    private volatile Throwable failure;
    private boolean accepting;

    public SingleWriterCommandApplier(StorageEngine engine, CollectionRegistry registry, RequestValidator validator,
                                      WriterConfig config, Runnable onFailure) {
        if (config.getQueueCapacity() < 1 || config.getRequestTimeout().isNegative() || config.getRequestTimeout().isZero())
            throw new IllegalArgumentException("Writer capacity and request timeout must be positive");
        processor = new CommandProcessor(engine, registry, validator, config.isSyncWrites());
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.getQueueCapacity()), runnable -> {
                    var thread = new Thread(runnable, "artemis-writer"); thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.onFailure = onFailure;
    }

    public void start(CollectionRegistry.Recovery recovery) {
        synchronized (admission) { processor.initialize(recovery); started = true; accepting = true; }
    }

    @Override public <R> CompletableFuture<ApplyOutcome<R>> submit(Command<R> command) {
        var future = new CompletableFuture<ApplyOutcome<R>>();
        synchronized (admission) {
            if (!accepting || failure != null) throw unavailable();
            try {
                executor.execute(() -> {
                    try {
                        ApplyOutcome<R> result;
                        synchronized (applyLock) {
                            requireHealthy();
                            if (processor.lastAppliedSeq() == Long.MAX_VALUE)
                                throw new VectorDbException(ErrorCode.RESOURCE_EXHAUSTED, "Sequence numbers exhausted");
                            result = process(processor.lastAppliedSeq() + 1, command);
                        }
                        // User callbacks must not run on the writer and deadlock shutdown/draining.
                        Thread.startVirtualThread(() -> future.complete(result));
                    } catch (Throwable e) { Thread.startVirtualThread(() -> future.completeExceptionally(e)); }
                });
            } catch (RejectedExecutionException e) {
                throw new VectorDbException(ErrorCode.RESOURCE_EXHAUSTED, "Writer queue is full", e);
            }
        }
        return future;
    }

    @Override public <R> ApplyOutcome<R> apply(long sequence, Command<R> command) {
        if (sequence <= 0) throw new IllegalArgumentException("Sequence must be positive");
        synchronized (applyLock) {
            synchronized (admission) { if (!accepting) throw unavailable(); }
            requireHealthy(); return process(sequence, command);
        }
    }

    private <R> ApplyOutcome<R> process(long sequence, Command<R> command) {
        try { return processor.apply(sequence, command); }
        catch (Throwable e) {
            failure = e; onFailure.run();
            if (e instanceof VectorDbException db) throw db;
            throw new VectorDbException(ErrorCode.INTERNAL, "Command apply failed; recovery required", e);
        }
    }
    public void requireHealthy() { if (!started || failure != null) throw unavailable(); }
    private VectorDbException unavailable() { return new VectorDbException(ErrorCode.UNAVAILABLE, "Writer unavailable", failure); }
    @Override public long lastAppliedSeq() { return processor.lastAppliedSeq(); }
    @Override public void close() {
        synchronized (admission) { accepting = false; executor.shutdown(); }
        boolean interrupted = false;
        for (;;) {
            try { if (executor.awaitTermination(1, TimeUnit.SECONDS)) break; }
            catch (InterruptedException e) { interrupted = true; }
        }
        synchronized (applyLock) { /* Wait for any accepted explicit apply too. */ }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
