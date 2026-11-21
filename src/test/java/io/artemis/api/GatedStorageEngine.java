package io.artemis.api;

import io.artemis.storage.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only control at the real storage boundaries used by API readers and writers. */
final class GatedStorageEngine implements StorageEngine {
    private final StorageEngine delegate;
    private final AtomicReference<Gate> commitGate = new AtomicReference<>();
    private final AtomicReference<Gate> searchGate = new AtomicReference<>();
    private final AtomicInteger snapshots = new AtomicInteger();
    private final AtomicReference<RuntimeException> commitFailure = new AtomicReference<>();
    GatedStorageEngine(StorageEngine delegate) { this.delegate = delegate; }
    Gate pauseCommit() { var gate = new Gate(); commitGate.set(gate); return gate; }
    Gate pauseSearch() { var gate = new Gate(); searchGate.set(gate); return gate; }
    int openSnapshots() { return snapshots.get(); }
    void failNextCommit(RuntimeException failure) { commitFailure.set(failure); }
    static final class Gate implements AutoCloseable {
        private final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        void reached() {
            entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Gate timed out"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        }
        void awaitEntry() throws Exception {
            if (!entered.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Operation did not enter gate");
        }
        @Override public void close() { release.countDown(); }
    }
    @Override public byte[] get(Namespace namespace, byte[] key) { return delegate.get(namespace, key); }
    @Override public WriteBatch newBatch() { return delegate.newBatch(); }
    @Override public void commit(WriteBatch batch, boolean sync) {
        var failure = commitFailure.getAndSet(null); if (failure != null) throw failure;
        var gate = commitGate.getAndSet(null); if (gate != null) gate.reached();
        delegate.commit(batch, sync);
    }
    @Override public Cursor scan(Namespace namespace, byte[] start, byte[] end) { return delegate.scan(namespace, start, end); }
    @Override public Snapshot snapshot() {
        var snapshot = delegate.snapshot();
        snapshots.incrementAndGet();
        var closed = new AtomicBoolean();
        return new Snapshot() {
            @Override public byte[] get(Namespace namespace, byte[] key) {
                if (namespace == Namespace.INT_TO_EXT) {
                    var gate = searchGate.getAndSet(null); if (gate != null) gate.reached();
                }
                return snapshot.get(namespace, key);
            }
            @Override public Cursor scan(Namespace namespace, byte[] start, byte[] end) { return snapshot.scan(namespace, start, end); }
            @Override public void close() {
                if (closed.compareAndSet(false, true)) { snapshot.close(); snapshots.decrementAndGet(); }
            }
        };
    }
    @Override public void close() { delegate.close(); }
}
