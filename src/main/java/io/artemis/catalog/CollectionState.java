package io.artemis.catalog;

import io.artemis.api.model.*;
import io.artemis.api.error.*;
import io.artemis.index.*;
import io.artemis.storage.StorageEngine;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Readers use private snapshots; during a commit they share a short-lived frozen old view. */
public final class CollectionState implements AutoCloseable {
    private final long id;
    private final CollectionConfig config;
    private final VectorIndex index;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final Object viewGuard = new Object();
    private final StorageEngine engine;
    private StorageEngine.Snapshot frozen;
    private long nextInternalId;
    private long vectorCount;
    private boolean closed;
    private boolean failed;

    public CollectionState(long id, CollectionConfig config, long nextInternalId, long vectorCount,
                           VectorIndex index, StorageEngine engine) {
        this.id = id; this.config = config; this.nextInternalId = nextInternalId;
        this.vectorCount = vectorCount; this.index = index; this.engine = engine;
    }

    public long id() { return id; }
    public CollectionConfig config() { return config; }
    /** Writer-only access; all mutations are serialized by the command processor. */
    public long nextInternalId() { return nextInternalId; }
    public long vectorCount() { return vectorCount; }

    public ReadLease read() {
        lock.readLock().lock();
        if (closed || failed) {
            lock.readLock().unlock();
            throw new VectorDbException(failed ? ErrorCode.UNAVAILABLE : ErrorCode.NOT_FOUND,
                    failed ? "Collection requires recovery" : "Collection was dropped");
        }
        try {
            synchronized (viewGuard) {
                return new ReadLease(frozen == null ? engine.snapshot() : frozen, frozen == null);
            }
        } catch (RuntimeException | Error e) { lock.readLock().unlock(); throw e; }
    }

    /** Capture the precommit view without waiting for readers or holding a lock across fsync. */
    public void freezeForCommit() {
        synchronized (viewGuard) {
            if (frozen != null || closed) throw new IllegalStateException("Invalid collection mutation state");
            frozen = engine.snapshot();
        }
    }

    public record Insertion(long internalId, float[] vector) {}

    /** Called only after a successful storage commit, never while holding the fsync path's lock. */
    public void publish(long nextId, long count, List<Long> deleted, List<Insertion> inserted) {
        lock.writeLock().lock();
        try {
            if (closed) throw new IllegalStateException("Collection is closed");
            deleted.forEach(index::markDeleted);
            for (var item : inserted) index.insert(item.internalId(), item.vector());
            nextInternalId = nextId;
            vectorCount = count;
            releaseFrozen();
        } catch (RuntimeException | Error failure) {
            failed = true;
            try { releaseFrozen(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        } finally { lock.writeLock().unlock(); }
    }

    /** Drain old readers and prevent any pre-admitted caller from using a failed publication. */
    public void invalidate() {
        lock.writeLock().lock();
        try { failed = true; releaseFrozen(); }
        finally { lock.writeLock().unlock(); }
    }

    private void releaseFrozen() {
        synchronized (viewGuard) {
            var previous = frozen;
            frozen = null;
            if (previous != null) previous.close();
        }
    }

    @Override public void close() {
        lock.writeLock().lock();
        try { if (!closed) { closed = true; releaseFrozen(); } }
        finally { lock.writeLock().unlock(); }
    }

    public final class ReadLease implements AutoCloseable {
        private final StorageEngine.Snapshot snapshot;
        private final boolean ownsSnapshot;
        private boolean released;
        private ReadLease(StorageEngine.Snapshot snapshot, boolean ownsSnapshot) {
            this.snapshot = snapshot; this.ownsSnapshot = ownsSnapshot;
        }
        private void check() { if (released) throw new IllegalStateException("Read lease is closed"); }
        public CollectionInfo info() { check(); return new CollectionInfo(id, config, vectorCount); }
        public StorageEngine.Snapshot snapshot() { check(); return snapshot; }
        public VectorIndex index() { check(); return index; }
        @Override public void close() {
            if (!released) {
                released = true;
                try { if (ownsSnapshot) snapshot.close(); } finally { lock.readLock().unlock(); }
            }
        }
    }
}
