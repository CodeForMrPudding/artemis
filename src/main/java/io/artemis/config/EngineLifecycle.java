package io.artemis.config;

import io.artemis.api.RequestValidator;
import io.artemis.api.error.*;
import io.artemis.catalog.CollectionRegistry;
import io.artemis.command.SingleWriterCommandApplier;
import io.artemis.storage.StorageEngine;
import io.micronaut.context.annotation.*;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.annotation.PreDestroy;

/** Recovery completes before READY; shutdown closes admission, drains, then releases snapshots/storage. */
@Context
@Requires(property = "vectordb.enabled", notEquals = "false")
public class EngineLifecycle implements AutoCloseable {
    public enum State { STARTING, READY, CLOSING }
    private final StorageEngine engine;
    private final WriterConfig config;
    private final CollectionRegistry registry = new CollectionRegistry();
    private final SingleWriterCommandApplier applier;
    private volatile State state = State.STARTING;
    private boolean closed;

    public EngineLifecycle(StorageEngine engine, RequestValidator validator, WriterConfig config) {
        this.engine = engine; this.config = config;
        applier = new SingleWriterCommandApplier(engine, registry, validator, config, () -> state = State.CLOSING);
    }
    public CollectionRegistry registry() { return registry; }
    public SingleWriterCommandApplier applier() { return applier; }
    public State state() { return state; }
    @EventListener public void onStartup(StartupEvent event) { start(); }
    public synchronized void start() {
        if (state == State.READY) return;
        if (closed || state != State.STARTING) throw new VectorDbException(ErrorCode.UNAVAILABLE, "Engine cannot start");
        try { applier.start(registry.recover(engine, config.isSyncWrites())); state = State.READY; }
        catch (RuntimeException e) {
            close();
            if (e instanceof VectorDbException db) throw db;
            throw new VectorDbException(ErrorCode.INTERNAL, "Recovery failed", e);
        } catch (Error e) { close(); throw e; }
    }
    public void requireReady() {
        if (state != State.READY) throw new VectorDbException(ErrorCode.UNAVAILABLE, "Engine is " + state);
        applier.requireHealthy();
    }
    @Override @PreDestroy public synchronized void close() {
        if (closed) return;
        closed = true; state = State.CLOSING;
        try { applier.close(); }
        finally { try { registry.close(); } finally { engine.close(); } }
    }
}
