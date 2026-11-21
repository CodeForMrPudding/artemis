package io.artemis.storage;

import io.artemis.api.error.ErrorCode;
import io.artemis.api.error.VectorDbException;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.ReadOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteOptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Durable byte storage with atomic cross-column-family commits and stable native snapshots. */
public final class RocksDbStorageEngine implements StorageEngine {
    static { RocksDB.loadLibrary(); }

    // Reads share this lock with commits. Only shutdown needs exclusive access.
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);
    private final Object writer = new Object();
    private final Set<Resource> resources = ConcurrentHashMap.newKeySet();
    private final EnumMap<Namespace, ColumnFamilyHandle> namespaces = new EnumMap<>(Namespace.class);
    private final List<ColumnFamilyHandle> handles = new ArrayList<>();
    private final List<ColumnFamilyOptions> familyOptions = new ArrayList<>();
    private final DBOptions options;
    private final RocksDB db;
    private boolean closed;

    /**
     * Creates a new database in an empty directory, or opens an existing Artemis
     * database with exactly the default and six named column families. Existing
     * default-only databases, including empty ones, are incompatible.
     */
    public RocksDbStorageEngine(Path directory) {
        Objects.requireNonNull(directory, "directory");
        boolean existing;
        try {
            Files.createDirectories(directory);
            existing = validateExistingLayout(directory);
        } catch (IOException | RocksDBException e) {
            throw failure("open", e);
        }
        options = new DBOptions().setCreateIfMissing(!existing).setCreateMissingColumnFamilies(!existing);
        RocksDB opened = null;
        try {
            var descriptors = new ArrayList<ColumnFamilyDescriptor>();
            descriptors.add(descriptor(RocksDB.DEFAULT_COLUMN_FAMILY));
            for (var namespace : Namespace.values()) {
                descriptors.add(descriptor(namespace.persistentName().getBytes(StandardCharsets.UTF_8)));
            }
            opened = RocksDB.open(options, directory.toString(), descriptors, handles);
            for (var namespace : Namespace.values()) namespaces.put(namespace, handles.get(namespace.ordinal() + 1));
            db = opened;
        } catch (RocksDBException e) {
            handles.forEach(ColumnFamilyHandle::close);
            if (opened != null) opened.close();
            familyOptions.forEach(ColumnFamilyOptions::close);
            options.close();
            throw failure("open", e);
        }
    }

    /** Inspect before writable open so a foreign or partial layout is never repaired or adopted. */
    private static boolean validateExistingLayout(Path directory) throws IOException, RocksDBException {
        try (var files = Files.list(directory)) {
            if (files.findAny().isEmpty()) return false;
        }
        try (var inspection = new Options()) {
            var names = RocksDB.listColumnFamilies(inspection, directory.toString());
            boolean compatible = names.size() == Namespace.values().length + 1
                    && names.stream().anyMatch(name -> Arrays.equals(name, RocksDB.DEFAULT_COLUMN_FAMILY));
            for (var namespace : Namespace.values()) {
                byte[] expected = namespace.persistentName().getBytes(StandardCharsets.UTF_8);
                compatible &= names.stream().anyMatch(name -> Arrays.equals(name, expected));
            }
            if (!compatible) {
                throw new VectorDbException(ErrorCode.INTERNAL,
                        "Existing storage directory has an incompatible column-family layout");
            }
            return true;
        }
    }

    private ColumnFamilyDescriptor descriptor(byte[] name) {
        var family = new ColumnFamilyOptions();
        familyOptions.add(family);
        return new ColumnFamilyDescriptor(name, family);
    }

    @Override
    public byte[] get(Namespace namespace, byte[] key) {
        return access(() -> db.get(handle(namespace), Objects.requireNonNull(key, "key")));
    }

    @Override
    public WriteBatch newBatch() {
        return access(() -> new Batch());
    }

    @Override
    public void commit(WriteBatch batch, boolean sync) {
        access(() -> {
            Objects.requireNonNull(batch, "batch");
            if (!(batch instanceof RocksDbStorageEngine.Batch own) || own.owner() != this) {
                throw new IllegalArgumentException("Batch belongs to a different engine");
            }
            own.requireOpen();
            synchronized (writer) {
                try (var nativeBatch = new org.rocksdb.WriteBatch();
                     var writes = new WriteOptions().setSync(sync).setDisableWAL(false)) {
                    var greatestStaged = new EnumMap<Namespace, byte[]>(Namespace.class);
                    for (var operation : own.operations) {
                        var family = handle(operation.namespace());
                        switch (operation.kind()) {
                            case PUT -> {
                                nativeBatch.put(family, operation.start(), operation.value());
                                byte[] greatest = greatestStaged.get(operation.namespace());
                                if (greatest == null || Arrays.compareUnsigned(operation.start(), greatest) > 0) {
                                    greatestStaged.put(operation.namespace(), operation.start());
                                }
                            }
                            case DELETE -> nativeBatch.delete(family, operation.start());
                            case DELETE_RANGE -> {
                                byte[] start = operation.start() == null ? new byte[0] : operation.start();
                                byte[] end = operation.end();
                                if (end == null) end = upperBound(family, greatestStaged.get(operation.namespace()));
                                if (end != null && Arrays.compareUnsigned(start, end) < 0) {
                                    nativeBatch.deleteRange(family, start, end);
                                }
                            }
                        }
                    }
                    db.write(writes, nativeBatch);
                } finally {
                    own.closeInternal();
                }
            }
            return null;
        });
    }

    /**
     * RocksDB needs a finite upper bound. Appending NUL to the greatest existing or
     * earlier staged key includes every key visible at this point in the batch,
     * including empty/all-FF keys. Later puts remain after the range tombstone.
     * The writer monitor prevents another commit from adding a larger key meanwhile.
     */
    private byte[] upperBound(ColumnFamilyHandle family, byte[] greatestStaged) throws RocksDBException {
        byte[] greatest = greatestStaged;
        try (var iterator = db.newIterator(family)) {
            iterator.seekToLast();
            if (iterator.isValid()) {
                byte[] existing = iterator.key();
                if (greatest == null || Arrays.compareUnsigned(existing, greatest) > 0) greatest = existing;
            }
            iterator.status();
        }
        return greatest == null ? null : Arrays.copyOf(greatest, greatest.length + 1);
    }

    @Override
    public Cursor scan(Namespace namespace, byte[] start, byte[] end) {
        return access(() -> {
            handle(namespace);
            validateRange(start, end);
            var snapshot = new View();
            try {
                return snapshot.cursor(namespace, start, end, true);
            } catch (RuntimeException | RocksDBException e) {
                snapshot.closeInternal();
                throw e;
            }
        });
    }

    @Override
    public Snapshot snapshot() {
        return access(View::new);
    }

    @Override
    public void close() {
        var lock = lifecycle.writeLock();
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            for (var resource : resources.toArray(Resource[]::new)) resource.closeInternal();
            handles.forEach(ColumnFamilyHandle::close);
            db.close();
            familyOptions.forEach(ColumnFamilyOptions::close);
            options.close();
        } finally {
            lock.unlock();
        }
    }

    private ColumnFamilyHandle handle(Namespace namespace) {
        return namespaces.get(Objects.requireNonNull(namespace, "namespace"));
    }

    private <T> T access(StorageCall<T> action) {
        var lock = lifecycle.readLock();
        lock.lock();
        try {
            if (closed) throw new IllegalStateException("Storage engine is closed");
            return action.run();
        } catch (RocksDBException e) {
            throw failure("operation", e);
        } finally {
            lock.unlock();
        }
    }

    private static VectorDbException failure(String operation, Exception cause) {
        return new VectorDbException(ErrorCode.INTERNAL, "Storage " + operation + " failed", cause);
    }

    private static void validateRange(byte[] start, byte[] end) {
        if (start != null && end != null && Arrays.compareUnsigned(start, end) > 0) {
            throw new IllegalArgumentException("Range start is after end");
        }
    }

    private static byte[] copy(byte[] bytes) { return bytes == null ? null : bytes.clone(); }

    @FunctionalInterface
    private interface StorageCall<T> { T run() throws RocksDBException; }

    private abstract class Resource implements AutoCloseable {
        boolean resourceClosed;

        final void register() { resources.add(this); }
        final void requireOpen() {
            if (resourceClosed) throw new IllegalStateException("Storage resource is closed or already committed");
        }
        abstract void closeInternal();

        @Override
        public final void close() {
            var lock = lifecycle.readLock();
            lock.lock();
            try { closeInternal(); }
            finally { lock.unlock(); }
        }
    }

    private enum Kind { PUT, DELETE, DELETE_RANGE }
    private record Operation(Kind kind, Namespace namespace, byte[] start, byte[] end, byte[] value) {}

    private final class Batch extends Resource implements WriteBatch {
        private final List<Operation> operations = new ArrayList<>();

        private Batch() { register(); }
        private RocksDbStorageEngine owner() { return RocksDbStorageEngine.this; }

        @Override
        public void put(Namespace namespace, byte[] key, byte[] value) {
            access(() -> {
                requireOpen();
                handle(namespace);
                byte[] copiedKey = Objects.requireNonNull(key, "key").clone();
                byte[] copiedValue = Objects.requireNonNull(value, "value").clone();
                operations.add(new Operation(Kind.PUT, namespace, copiedKey, null, copiedValue));
                return null;
            });
        }

        @Override
        public void delete(Namespace namespace, byte[] key) {
            access(() -> {
                requireOpen();
                handle(namespace);
                operations.add(new Operation(Kind.DELETE, namespace, Objects.requireNonNull(key, "key").clone(), null, null));
                return null;
            });
        }

        @Override
        public void deleteRange(Namespace namespace, byte[] start, byte[] end) {
            access(() -> {
                requireOpen();
                handle(namespace);
                validateRange(start, end);
                operations.add(new Operation(Kind.DELETE_RANGE, namespace, copy(start), copy(end), null));
                return null;
            });
        }

        @Override
        void closeInternal() {
            if (resourceClosed) return;
            resourceClosed = true;
            operations.clear();
            resources.remove(this);
        }
    }

    private final class View extends Resource implements Snapshot {
        private final org.rocksdb.Snapshot captured = db.getSnapshot();
        // Never mutated after construction: shared snapshot reads use const native
        // ReadOptions, while each scan owns its iterator. Callers coordinate close.
        private final ReadOptions reads = new ReadOptions().setSnapshot(captured);
        private final Set<Scan> cursors = ConcurrentHashMap.newKeySet();

        private View() { register(); }

        @Override
        public byte[] get(Namespace namespace, byte[] key) {
            return access(() -> {
                requireOpen();
                return db.get(handle(namespace), reads, Objects.requireNonNull(key, "key"));
            });
        }

        @Override
        public Cursor scan(Namespace namespace, byte[] start, byte[] end) {
            return access(() -> {
                requireOpen();
                return cursor(namespace, start, end, false);
            });
        }

        private Scan cursor(Namespace namespace, byte[] start, byte[] end, boolean ownsView) throws RocksDBException {
            var family = handle(namespace);
            validateRange(start, end);
            var iterator = db.newIterator(family, reads);
            try {
                if (start == null) iterator.seekToFirst();
                else iterator.seek(start);
                iterator.status();
                return new Scan(this, iterator, copy(end), ownsView);
            } catch (RuntimeException | RocksDBException e) {
                iterator.close();
                throw e;
            }
        }

        @Override
        void closeInternal() {
            if (resourceClosed) return;
            resourceClosed = true;
            for (var cursor : cursors.toArray(Scan[]::new)) cursor.closeInternal();
            reads.close();
            db.releaseSnapshot(captured);
            captured.close();
            resources.remove(this);
        }
    }

    private final class Scan extends Resource implements Cursor {
        private final View view;
        private final RocksIterator iterator;
        private final byte[] end;
        private final boolean ownsView;

        private Scan(View view, RocksIterator iterator, byte[] end, boolean ownsView) {
            this.view = view;
            this.iterator = iterator;
            this.end = end;
            this.ownsView = ownsView;
            view.cursors.add(this);
            register();
        }

        private boolean available() throws RocksDBException {
            requireOpen();
            view.requireOpen();
            iterator.status();
            return iterator.isValid() && (end == null || Arrays.compareUnsigned(iterator.key(), end) < 0);
        }

        @Override
        public boolean hasNext() { return access(this::available); }

        @Override
        public Entry next() {
            return access(() -> {
                if (!available()) throw new NoSuchElementException();
                var entry = new Entry(iterator.key(), iterator.value());
                iterator.next();
                iterator.status();
                return entry;
            });
        }

        @Override
        void closeInternal() {
            if (resourceClosed) return;
            resourceClosed = true;
            iterator.close();
            view.cursors.remove(this);
            resources.remove(this);
            if (ownsView) view.closeInternal();
        }
    }
}
