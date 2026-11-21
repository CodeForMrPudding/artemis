package io.artemis.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.artemis.storage.Namespace.*;
import static org.junit.jupiter.api.Assertions.*;

class InMemoryStorageEngineTest {
    @ParameterizedTest
    @EnumSource(Namespace.class)
    void everyNamespaceIsIsolatedAndSupportsEmptyBinaryValues(Namespace namespace) {
        try (var engine = new InMemoryStorageEngine(); var batch = engine.newBatch()) {
            batch.put(namespace, new byte[0], new byte[0]);
            engine.commit(batch, true);
            for (var other : Namespace.values()) {
                if (other == namespace) assertArrayEquals(new byte[0], engine.get(other, new byte[0]));
                else assertNull(engine.get(other, new byte[0]));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void batchIsInvisibleUntilCommitAndLastOperationWins(boolean sync) {
        try (StorageEngine engine = new InMemoryStorageEngine(); var batch = engine.newBatch()) {
            batch.put(VECTORS, b(1), b(10));
            batch.put(METADATA, b(1), b(20));
            batch.delete(VECTORS, b(1));
            batch.put(VECTORS, b(1), b(30));
            assertNull(engine.get(VECTORS, b(1)));
            assertNull(engine.get(METADATA, b(1)));
            engine.commit(batch, sync);
            assertArrayEquals(b(30), engine.get(VECTORS, b(1)));
            assertArrayEquals(b(20), engine.get(METADATA, b(1)));
            assertNull(engine.get(CATALOG, b(1)));
            assertThrows(IllegalStateException.class, () -> engine.commit(batch, sync));
            assertThrows(IllegalStateException.class, () -> batch.put(VECTORS, b(2), b(2)));
        }
    }

    @Test
    void closedOrForeignBatchesCannotChangeState() {
        try (var first = new InMemoryStorageEngine(); var second = new InMemoryStorageEngine()) {
            var abandoned = first.newBatch();
            abandoned.put(VECTORS, b(1), b(10));
            abandoned.close();
            abandoned.close();
            assertThrows(IllegalStateException.class, () -> first.commit(abandoned, true));
            try (var foreign = second.newBatch()) {
                foreign.put(VECTORS, b(1), b(11));
                assertThrows(IllegalArgumentException.class, () -> first.commit(foreign, true));
                assertNull(first.get(VECTORS, b(1)));
                second.commit(foreign, true);
                assertArrayEquals(b(11), second.get(VECTORS, b(1)));
            }
            assertNull(first.get(VECTORS, b(1)));
        }
    }

    @Test
    void copiesBuffersAtEveryOwnershipBoundary() {
        try (var engine = new InMemoryStorageEngine(); var batch = engine.newBatch()) {
            byte[] key = b(1), value = b(10);
            batch.put(VECTORS, key, value);
            key[0] = 2;
            value[0] = 99;
            engine.commit(batch, true);
            byte[] returned = engine.get(VECTORS, b(1));
            returned[0] = 88;
            assertNull(engine.get(VECTORS, b(2)));
            assertArrayEquals(b(10), engine.get(VECTORS, b(1)));
            try (var cursor = engine.scan(VECTORS, null, null)) {
                var entry = cursor.next();
                byte[] entryKey = entry.key(), entryValue = entry.value();
                entryKey[0] = 7;
                entryValue[0] = 8;
                assertArrayEquals(b(1), entry.key());
                assertArrayEquals(b(10), entry.value());
            }
            var entry = new StorageEngine.Entry(key, value);
            key[0] = 3;
            value[0] = 4;
            assertArrayEquals(b(2), entry.key());
            assertArrayEquals(b(99), entry.value());
        }
    }

    @Test
    void scansAreUnsignedOrderedStableAndHalfOpen() {
        try (var engine = new InMemoryStorageEngine(); var batch = engine.newBatch()) {
            for (int key : new int[]{255, 128, 127, 0, 1}) batch.put(VECTORS, b(key), b(key));
            engine.commit(batch, true);
            try (var cursor = engine.scan(VECTORS, b(1), b(255)); var update = engine.newBatch()) {
                update.delete(VECTORS, b(128));
                engine.commit(update, true);
                assertEquals(List.of(1, 127, 128), keys(cursor));
                assertFalse(cursor.hasNext());
                assertThrows(NoSuchElementException.class, cursor::next);
            }
            try (var cursor = engine.scan(VECTORS, null, null)) {
                assertEquals(List.of(0, 1, 127, 255), keys(cursor));
            }
            try (var cursor = engine.scan(VECTORS, b(127), b(127))) {
                assertFalse(cursor.hasNext());
            }
            assertThrows(IllegalArgumentException.class, () -> engine.scan(VECTORS, b(128), b(127)));
        }
    }

    @Test
    void snapshotsKeepConsistentOldValuesAcrossNamespacesAndDeletion() {
        try (var engine = new InMemoryStorageEngine()) {
            putPair(engine, 1);
            try (var snapshot = engine.snapshot()) {
                putPair(engine, 2);
                try (var batch = engine.newBatch()) {
                    batch.delete(VECTORS, b(1));
                    engine.commit(batch, true);
                }
                assertArrayEquals(b(1), snapshot.get(VECTORS, b(1)));
                assertArrayEquals(b(1), snapshot.get(METADATA, b(1)));
                byte[] returned = snapshot.get(VECTORS, b(1));
                returned[0] = 99;
                assertArrayEquals(b(1), snapshot.get(VECTORS, b(1)));
                try (var cursor = snapshot.scan(VECTORS, null, null)) {
                    assertEquals(List.of(1), keys(cursor));
                }
                assertNull(engine.get(VECTORS, b(1)));
                assertArrayEquals(b(2), engine.get(METADATA, b(1)));
            }
        }
    }

    @Test
    void deleteRangeIsAtomicHalfOpenAndOrderedWithinBatch() {
        try (var engine = new InMemoryStorageEngine(); var batch = engine.newBatch()) {
            for (int i = 0; i < 5; i++) batch.put(VECTORS, b(i), b(i));
            batch.deleteRange(VECTORS, b(1), b(4));
            batch.put(VECTORS, b(2), b(22));
            engine.commit(batch, true);
            try (var cursor = engine.scan(VECTORS, null, null)) {
                assertEquals(List.of(0, 2, 4), keys(cursor));
            }
            assertArrayEquals(b(22), engine.get(VECTORS, b(2)));
            try (var clear = engine.newBatch()) {
                clear.deleteRange(VECTORS, null, null);
                clear.delete(VECTORS, b(99));
                engine.commit(clear, false);
            }
            try (var cursor = engine.scan(VECTORS, null, null)) { assertFalse(cursor.hasNext()); }
        }
    }

    @Test
    void copiesDeleteKeysAndRangeBoundsBeforeCommit() {
        try (var engine = new InMemoryStorageEngine(); var batch = engine.newBatch()) {
            for (int i = 0; i < 5; i++) batch.put(VECTORS, b(i), b(i));
            byte[] key = b(0), start = b(1), end = b(3);
            batch.delete(VECTORS, key);
            batch.deleteRange(VECTORS, start, end);
            key[0] = 4;
            start[0] = 3;
            end[0] = 5;
            engine.commit(batch, true);
            try (var cursor = engine.scan(VECTORS, null, null)) {
                assertEquals(List.of(3, 4), keys(cursor));
            }
        }
    }

    @Test
    void rejectsInvalidOperationsBeforeTheyEnterTheBatch() {
        try (var engine = new InMemoryStorageEngine(); var batch = engine.newBatch()) {
            batch.put(VECTORS, b(1), b(1));
            assertThrows(NullPointerException.class, () -> batch.put(null, b(2), b(2)));
            assertThrows(NullPointerException.class, () -> batch.put(VECTORS, null, b(2)));
            assertThrows(NullPointerException.class, () -> batch.put(VECTORS, b(2), null));
            assertThrows(NullPointerException.class, () -> batch.delete(VECTORS, null));
            assertThrows(NullPointerException.class, () -> batch.deleteRange(null, null, null));
            assertThrows(IllegalArgumentException.class, () -> batch.deleteRange(VECTORS, b(3), b(2)));
            engine.commit(batch, true);
            try (var cursor = engine.scan(VECTORS, null, null)) { assertEquals(List.of(1), keys(cursor)); }
            assertThrows(NullPointerException.class, () -> engine.get(null, b(1)));
            assertThrows(NullPointerException.class, () -> engine.get(VECTORS, null));
            assertThrows(NullPointerException.class, () -> engine.commit(null, true));
        }
    }

    @Test
    void prefixRangeDeletesOnlyTheChosenCollection() {
        try (var engine = new InMemoryStorageEngine(); var batch = engine.newBatch()) {
            for (long collection : new long[]{255, 256, Long.MAX_VALUE}) {
                batch.put(VECTORS, KeyCodec.vectorKey(collection, Long.MAX_VALUE), b(1));
            }
            byte[] prefix = KeyCodec.collectionPrefix(256);
            batch.deleteRange(VECTORS, prefix, KeyCodec.prefixUpperBound(prefix));
            engine.commit(batch, true);
            assertNotNull(engine.get(VECTORS, KeyCodec.vectorKey(255, Long.MAX_VALUE)));
            assertNull(engine.get(VECTORS, KeyCodec.vectorKey(256, Long.MAX_VALUE)));
            assertNotNull(engine.get(VECTORS, KeyCodec.vectorKey(Long.MAX_VALUE, Long.MAX_VALUE)));
        }
    }

    @Test
    void closingResourcesIsIdempotentAndInvalidatesDependentOperations() {
        var engine = new InMemoryStorageEngine();
        var cursor = engine.scan(VECTORS, null, null);
        cursor.close();
        cursor.close();
        assertThrows(IllegalStateException.class, cursor::hasNext);
        assertThrows(IllegalStateException.class, cursor::next);
        var snapshot = engine.snapshot();
        var snapshotCursor = snapshot.scan(VECTORS, null, null);
        snapshot.close();
        snapshot.close();
        assertThrows(IllegalStateException.class, () -> snapshot.get(VECTORS, b(1)));
        assertThrows(IllegalStateException.class, () -> snapshot.scan(VECTORS, null, null));
        assertThrows(IllegalStateException.class, snapshotCursor::hasNext);
        snapshotCursor.close();
        var liveSnapshot = engine.snapshot();
        var liveCursor = engine.scan(VECTORS, null, null);
        var batch = engine.newBatch();
        engine.close();
        engine.close();
        assertThrows(IllegalStateException.class, () -> engine.get(VECTORS, b(1)));
        assertThrows(IllegalStateException.class, engine::newBatch);
        assertThrows(IllegalStateException.class, engine::snapshot);
        assertThrows(IllegalStateException.class, () -> engine.scan(VECTORS, null, null));
        assertThrows(IllegalStateException.class, () -> engine.commit(batch, true));
        assertThrows(IllegalStateException.class, () -> batch.put(VECTORS, b(1), b(1)));
        assertThrows(IllegalStateException.class, () -> liveSnapshot.get(VECTORS, b(1)));
        assertThrows(IllegalStateException.class, liveCursor::hasNext);
        liveSnapshot.close();
        liveCursor.close();
        batch.close();
    }

    @Test
    void snapshotsNeverSeeHalfOfConcurrentMultiNamespaceCommits() throws Exception {
        try (var engine = new InMemoryStorageEngine(); var executor = Executors.newSingleThreadExecutor()) {
            putPair(engine, 0);
            var writer = executor.submit(() -> {
                for (int i = 1; i <= 300; i++) putPair(engine, i);
            });
            for (int i = 0; i < 300; i++) {
                try (var snapshot = engine.snapshot()) {
                    assertArrayEquals(snapshot.get(VECTORS, b(1)), snapshot.get(METADATA, b(1)));
                }
            }
            writer.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void seededOperationsMatchSimpleMapModel() {
        var random = new Random(1729);
        var model = new TreeMap<Integer, Integer>();
        try (var engine = new InMemoryStorageEngine()) {
            for (int i = 0; i < 100; i++) {
                try (var batch = engine.newBatch()) {
                    for (int j = 0; j < 10; j++) {
                        int key = random.nextInt(256);
                        if (random.nextBoolean()) {
                            int value = random.nextInt(256);
                            batch.put(VECTORS, b(key), b(value));
                            model.put(key, value);
                        } else {
                            batch.delete(VECTORS, b(key));
                            model.remove(key);
                        }
                    }
                    engine.commit(batch, true);
                }
                try (var cursor = engine.scan(VECTORS, null, null)) {
                    for (var expected : model.entrySet()) {
                        assertTrue(cursor.hasNext());
                        var actual = cursor.next();
                        assertArrayEquals(b(expected.getKey()), actual.key());
                        assertArrayEquals(b(expected.getValue()), actual.value());
                    }
                    assertFalse(cursor.hasNext());
                }
            }
        }
    }

    private static void putPair(StorageEngine engine, int value) {
        try (var batch = engine.newBatch()) {
            batch.put(VECTORS, b(1), b(value));
            batch.put(METADATA, b(1), b(value));
            engine.commit(batch, true);
        }
    }

    private static List<Integer> keys(StorageEngine.Cursor cursor) {
        var result = new ArrayList<Integer>();
        while (cursor.hasNext()) result.add(Byte.toUnsignedInt(cursor.next().key()[0]));
        return result;
    }

    private static byte[] b(int value) { return new byte[]{(byte) value}; }
}
