package io.artemis.storage.spike;

import io.artemis.api.error.ErrorCode;
import io.artemis.api.error.VectorDbException;
import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(startApplication = false)
class RocksDbSpikeTest {
    @Inject RocksDbSpike spike;
    @TempDir Path directory;

    @Test
    void syncedBatchSurvivesReopenWithAllValuesAndDeletion() throws Exception {
        Path dbPath = directory.resolve("db");
        spike.write(dbPath);

        // Independent readback: literals deliberately do not reuse spike fixtures.
        try (var options = new Options();
             var db = RocksDB.openReadOnly(options, dbPath.toString())) {
            assertArrayEquals(ByteBuffer.allocate(12).putFloat(1).putFloat(-2).putFloat(3.5f).array(),
                    db.get(bytes("vector/1")));
            assertArrayEquals(bytes("{\"kind\":\"spike\"}"), db.get(bytes("metadata/1")));
            assertArrayEquals(ByteBuffer.allocate(8).putLong(1).array(), db.get(bytes("last_applied_seq")));
            assertNull(db.get(bytes("obsolete")));
            assertNull(db.get(bytes("missing")));
        }
        spike.verify(dbPath);
    }

    @Test
    void roundtripCanBeRepeatedWithoutLeakingTheDatabaseLock() {
        Path dbPath = directory.resolve("db");
        spike.roundtrip(dbPath);
        spike.roundtrip(dbPath);
    }

    @Test
    void invalidDatabasePathRetainsRocksDbCauseAndInternalCode() throws Exception {
        Path file = Files.writeString(directory.resolve("file"), "not a directory");
        var error = assertThrows(VectorDbException.class, () -> spike.write(file));
        assertEquals(ErrorCode.INTERNAL, error.getCode());
        assertInstanceOf(RocksDBException.class, error.getCause());
    }

    @Test
    void verificationOfMissingDatabaseFailsWithoutCreatingIt() {
        Path missing = directory.resolve("missing-db");
        var error = assertThrows(VectorDbException.class, () -> spike.verify(missing));
        assertEquals(ErrorCode.INTERNAL, error.getCode());
        assertFalse(Files.exists(missing));
    }

    @Test
    void verificationDetectsIncompleteOrChangedBatchContents() throws Exception {
        Path dbPath = directory.resolve("db");
        spike.write(dbPath);
        try (var options = new Options(); var db = RocksDB.open(options, dbPath.toString())) {
            db.delete(bytes("metadata/1"));
        }
        var error = assertThrows(VectorDbException.class, () -> spike.verify(dbPath));
        assertEquals(ErrorCode.INTERNAL, error.getCode());
    }

    @Test
    void disablingSyncCannotSilentlyWeakenTheDurabilitySpike() {
        Path dbPath = directory.resolve("unsynced-db");
        try (var context = ApplicationContext.run(Map.of("vectordb.writer.sync-writes", false))) {
            var configured = context.getBean(RocksDbSpike.class);
            var error = assertThrows(VectorDbException.class, () -> configured.write(dbPath));
            assertEquals(ErrorCode.INVALID_ARGUMENT, error.getCode());
            assertFalse(Files.exists(dbPath));
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
