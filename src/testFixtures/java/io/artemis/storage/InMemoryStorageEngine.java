package io.artemis.storage;

import java.util.*;

/**
 * Volatile contract fake for service/applier tests. Both sync modes are accepted;
 * neither persists data. Commits publish a copied state under one monitor, keeping
 * snapshots and scans stable. Deliberately favors clarity over large-data performance.
 * It is not a Micronaut bean and is never selected as the production engine.
 */
public final class InMemoryStorageEngine implements StorageEngine {
    private EnumMap<Namespace, NavigableMap<Bytes, byte[]>> data = emptyData();
    private boolean closed;

    @Override
    public synchronized byte[] get(Namespace namespace, byte[] key) {
        requireOpen();
        return read(data, namespace, key);
    }

    @Override
    public synchronized WriteBatch newBatch() {
        requireOpen();
        return new MemoryBatch(this);
    }

    @Override
    public synchronized void commit(WriteBatch batch, boolean sync) {
        requireOpen();
        Objects.requireNonNull(batch, "batch");
        if (!(batch instanceof MemoryBatch own) || own.owner != this) {
            throw new IllegalArgumentException("Batch belongs to a different engine");
        }
        own.requireOpen();
        var next = emptyData();
        for (var namespace : Namespace.values()) next.get(namespace).putAll(data.get(namespace));
        for (var op : own.operations) {
            var table = next.get(op.namespace());
            switch (op.kind()) {
                case PUT -> table.put(op.start(), op.value());
                case DELETE -> table.remove(op.start());
                case DELETE_RANGE -> range(table, op.start(), op.end()).clear();
            }
        }
        data = next;
        own.close();
    }

    @Override
    public synchronized Cursor scan(Namespace namespace, byte[] start, byte[] end) {
        requireOpen();
        return cursor(data, namespace, start, end, null);
    }

    @Override
    public synchronized Snapshot snapshot() {
        requireOpen();
        return new MemorySnapshot(data);
    }

    @Override
    public synchronized void close() {
        closed = true;
        data = emptyData();
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Storage engine is closed");
    }

    private static EnumMap<Namespace, NavigableMap<Bytes, byte[]>> emptyData() {
        var result = new EnumMap<Namespace, NavigableMap<Bytes, byte[]>>(Namespace.class);
        for (var namespace : Namespace.values()) result.put(namespace, new TreeMap<>());
        return result;
    }

    private static byte[] read(Map<Namespace, NavigableMap<Bytes, byte[]>> source, Namespace namespace, byte[] key) {
        byte[] value = source.get(Objects.requireNonNull(namespace, "namespace")).get(new Bytes(key));
        return value == null ? null : value.clone();
    }

    private Cursor cursor(Map<Namespace, NavigableMap<Bytes, byte[]>> source, Namespace namespace,
                          byte[] start, byte[] end, MemorySnapshot snapshot) {
        var table = source.get(Objects.requireNonNull(namespace, "namespace"));
        var entries = new ArrayList<Entry>();
        range(table, bound(start), bound(end)).forEach((key, value) -> entries.add(new Entry(key.value, value)));
        return new MemoryCursor(entries.iterator(), snapshot);
    }

    private static Bytes bound(byte[] value) { return value == null ? null : new Bytes(value); }

    private static NavigableMap<Bytes, byte[]> range(NavigableMap<Bytes, byte[]> table, Bytes start, Bytes end) {
        validateRange(start, end);
        if (start == null) return end == null ? table : table.headMap(end, false);
        return end == null ? table.tailMap(start, true) : table.subMap(start, true, end, false);
    }

    private static void validateRange(Bytes start, Bytes end) {
        if (start != null && end != null && start.compareTo(end) > 0) {
            throw new IllegalArgumentException("Range start is after end");
        }
    }

    private enum Kind { PUT, DELETE, DELETE_RANGE }
    private record Operation(Kind kind, Namespace namespace, Bytes start, Bytes end, byte[] value) {}

    private static final class MemoryBatch implements WriteBatch {
        private final InMemoryStorageEngine owner;
        private final List<Operation> operations = new ArrayList<>();
        private boolean closed;

        private MemoryBatch(InMemoryStorageEngine owner) { this.owner = owner; }

        private void requireOpen() {
            owner.requireOpen();
            if (closed) throw new IllegalStateException("Batch is closed or already committed");
        }

        @Override
        public void put(Namespace namespace, byte[] key, byte[] value) {
            synchronized (owner) {
                requireOpen();
                operations.add(new Operation(Kind.PUT, Objects.requireNonNull(namespace, "namespace"),
                        new Bytes(key), null, Objects.requireNonNull(value, "value").clone()));
            }
        }

        @Override
        public void delete(Namespace namespace, byte[] key) {
            synchronized (owner) {
                requireOpen();
                operations.add(new Operation(Kind.DELETE, Objects.requireNonNull(namespace, "namespace"),
                        new Bytes(key), null, null));
            }
        }

        @Override
        public void deleteRange(Namespace namespace, byte[] start, byte[] end) {
            synchronized (owner) {
                requireOpen();
                Objects.requireNonNull(namespace, "namespace");
                Bytes lower = bound(start), upper = bound(end);
                validateRange(lower, upper);
                operations.add(new Operation(Kind.DELETE_RANGE, namespace, lower, upper, null));
            }
        }

        @Override
        public void close() {
            synchronized (owner) {
                closed = true;
                operations.clear();
            }
        }
    }

    private final class MemorySnapshot implements Snapshot {
        private final Map<Namespace, NavigableMap<Bytes, byte[]>> captured;
        private boolean closed;

        private MemorySnapshot(Map<Namespace, NavigableMap<Bytes, byte[]>> captured) { this.captured = captured; }

        private void requireOpen() {
            InMemoryStorageEngine.this.requireOpen();
            if (closed) throw new IllegalStateException("Snapshot is closed");
        }

        @Override
        public byte[] get(Namespace namespace, byte[] key) {
            synchronized (InMemoryStorageEngine.this) {
                requireOpen();
                return read(captured, namespace, key);
            }
        }

        @Override
        public Cursor scan(Namespace namespace, byte[] start, byte[] end) {
            synchronized (InMemoryStorageEngine.this) {
                requireOpen();
                return cursor(captured, namespace, start, end, this);
            }
        }

        @Override
        public void close() {
            synchronized (InMemoryStorageEngine.this) { closed = true; }
        }
    }

    private final class MemoryCursor implements Cursor {
        private final Iterator<Entry> entries;
        private final MemorySnapshot snapshot;
        private boolean closed;

        private MemoryCursor(Iterator<Entry> entries, MemorySnapshot snapshot) {
            this.entries = entries;
            this.snapshot = snapshot;
        }

        private void requireOpen() {
            InMemoryStorageEngine.this.requireOpen();
            if (closed) throw new IllegalStateException("Cursor is closed");
            if (snapshot != null) snapshot.requireOpen();
        }

        @Override
        public boolean hasNext() {
            synchronized (InMemoryStorageEngine.this) {
                requireOpen();
                return entries.hasNext();
            }
        }

        @Override
        public Entry next() {
            synchronized (InMemoryStorageEngine.this) {
                requireOpen();
                return entries.next();
            }
        }

        @Override
        public void close() {
            synchronized (InMemoryStorageEngine.this) { closed = true; }
        }
    }

    private static final class Bytes implements Comparable<Bytes> {
        private final byte[] value;

        private Bytes(byte[] value) { this.value = Objects.requireNonNull(value, "key").clone(); }
        @Override public int compareTo(Bytes other) { return Arrays.compareUnsigned(value, other.value); }
        @Override public boolean equals(Object other) {
            return other instanceof Bytes bytes && Arrays.equals(value, bytes.value);
        }
        @Override public int hashCode() { return Arrays.hashCode(value); }
    }
}
