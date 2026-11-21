package io.artemis.storage;

import java.util.Iterator;
import java.util.Objects;

/**
 * Engine-independent, byte-oriented storage. Engine methods are thread-safe;
 * batches and cursors are confined to their owning caller. Snapshot read methods
 * may be called concurrently; callers must exclude snapshot close until all
 * snapshot reads and dependent cursor operations have finished.
 *
 * Keys sort unsigned-lexicographically. All ranges are [start, end), with null
 * bounds meaning unbounded; reversed bounds are illegal, equal bounds are empty.
 * Keys and values may be empty but not null. Inputs are copied before retention;
 * returned arrays belong to the caller. Missing keys return null.
 *
 * Close all child resources before closing the engine. close() is idempotent;
 * using a closed resource or a child of a closed engine throws IllegalStateException.
 * Operational storage failures use VectorDbException(INTERNAL), without engine types
 * in these signatures. Null arguments and invalid ranges are programming errors
 * (NullPointerException and IllegalArgumentException, respectively).
 */
public interface StorageEngine extends AutoCloseable {
    byte[] get(Namespace namespace, byte[] key);

    WriteBatch newBatch();

    /**
     * Publish all operations atomically across namespaces in insertion order.
     * A batch belongs to this engine and may commit only once; foreign batches
     * throw IllegalArgumentException. sync=true requests durable acknowledgment
     * from durable implementations. Volatile test fixtures cannot offer durability.
     */
    void commit(WriteBatch batch, boolean sync);

    /** A stable view at scan creation time; callers must close even partially consumed cursors. */
    Cursor scan(Namespace namespace, byte[] start, byte[] end);

    /** A single consistent point in time across all namespaces, unaffected by later commits. */
    Snapshot snapshot();

    @Override
    void close();

    interface WriteBatch extends AutoCloseable {
        void put(Namespace namespace, byte[] key, byte[] value);

        /** Deleting a missing key is harmless. */
        void delete(Namespace namespace, byte[] key);

        /** Atomic range deletion, also ordered relative to other operations in this batch. */
        void deleteRange(Namespace namespace, byte[] start, byte[] end);

        /** Closing an uncommitted batch discards its staged operations. */
        @Override
        void close();
    }

    interface Snapshot extends AutoCloseable {
        byte[] get(Namespace namespace, byte[] key);

        /** The cursor depends on this snapshot remaining open. */
        Cursor scan(Namespace namespace, byte[] start, byte[] end);

        @Override
        void close();
    }

    /** remove() is unsupported; next() on an exhausted open cursor throws NoSuchElementException. */
    interface Cursor extends Iterator<Entry>, AutoCloseable {
        @Override
        void close();
    }

    /** Defensive carrier; compare byte contents explicitly, as record array equality is referential. */
    record Entry(byte[] key, byte[] value) {
        public Entry {
            key = Objects.requireNonNull(key, "key").clone();
            value = Objects.requireNonNull(value, "value").clone();
        }

        @Override public byte[] key() { return key.clone(); }
        @Override public byte[] value() { return value.clone(); }
    }
}
