package io.artemis.storage.spike;

import io.artemis.api.error.ErrorCode;
import io.artemis.api.error.VectorDbException;
import io.artemis.config.WriterConfig;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Feasibility experiment, not the production storage adapter or on-disk format.
 * Only run against a dedicated disposable database directory.
 */
@Singleton
@RequiredArgsConstructor
@Slf4j
public class RocksDbSpike {
    private final WriterConfig writerConfig;

    static {
        RocksDB.loadLibrary();
    }

    /** Commit a known multi-key batch with WAL enabled, then verify its contents. */
    public void write(Path directory) {
        if (!writerConfig.isSyncWrites()) {
            throw new VectorDbException(ErrorCode.INVALID_ARGUMENT,
                    "The durability spike requires vectordb.writer.sync-writes=true");
        }
        try (var options = new Options().setCreateIfMissing(true);
             var db = RocksDB.open(options, directory.toString());
             var writes = new WriteOptions().setSync(true).setDisableWAL(false);
             var batch = new WriteBatch()) {
            db.put(writes, bytes("obsolete"), bytes("remove me"));
            batch.put(bytes("vector/1"), vector());
            batch.put(bytes("metadata/1"), bytes("{\"kind\":\"spike\"}"));
            batch.put(bytes("last_applied_seq"), sequence());
            batch.delete(bytes("obsolete"));
            db.write(writes, batch);
            verifyContents(db);
            log.info("Synced RocksDB batch committed: {}", directory);
        } catch (RocksDBException e) {
            throw new VectorDbException(ErrorCode.INTERNAL, "RocksDB write failed: " + directory, e);
        }
    }

    /** Open an existing database read-only and check it without repairing or creating it. */
    public void verify(Path directory) {
        try (var options = new Options();
             var db = RocksDB.openReadOnly(options, directory.toString())) {
            verifyContents(db);
            log.info("Persisted RocksDB batch verified: {}", directory);
        } catch (RocksDBException e) {
            throw new VectorDbException(ErrorCode.INTERNAL, "RocksDB read failed: " + directory, e);
        }
    }

    public void roundtrip(Path directory) {
        write(directory);
        verify(directory);
    }

    private static void verifyContents(RocksDB db) throws RocksDBException {
        requireValue(db, "vector/1", vector());
        requireValue(db, "metadata/1", bytes("{\"kind\":\"spike\"}"));
        requireValue(db, "last_applied_seq", sequence());
        requireValue(db, "obsolete", null);
        requireValue(db, "missing", null);
    }

    private static void requireValue(RocksDB db, String key, byte[] expected) throws RocksDBException {
        if (!Arrays.equals(expected, db.get(bytes(key)))) {
            throw new VectorDbException(ErrorCode.INTERNAL, "RocksDB spike value mismatch: " + key);
        }
    }

    private static byte[] vector() {
        return ByteBuffer.allocate(12).putFloat(1).putFloat(-2).putFloat(3.5f).array();
    }

    private static byte[] sequence() {
        return ByteBuffer.allocate(8).putLong(1).array();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
