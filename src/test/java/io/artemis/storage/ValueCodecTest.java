package io.artemis.storage;

import io.artemis.api.error.ErrorCode;
import io.artemis.api.error.VectorDbException;
import io.artemis.api.model.CollectionConfig;
import io.artemis.api.model.DistanceMetric;
import io.artemis.api.model.HnswParams;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ValueCodecTest {
    // Literal fixtures protect the storage contract across process and implementation changes.
    private static final String CATALOG = "000000010000000000000007000000017800000002000000024c32"
            + "00000010000000c80000003200000000000000030000000000000002";

    @Test
    void longsUseEightBigEndianBytesAcrossTheNonnegativeRange() {
        assertEquals("0000000000000100", hex(ValueCodec.encodeLong(256)));
        assertEquals(256, ValueCodec.decodeLong(bytes("0000000000000100")));
        for (long value : new long[]{0, 1, Long.MAX_VALUE}) {
            assertEquals(value, ValueCodec.decodeLong(ValueCodec.encodeLong(value)));
        }
        assertThrows(IllegalArgumentException.class, () -> ValueCodec.encodeLong(-1));
    }

    @Test
    void corruptLongsFailAsInternalErrorsWithDiagnosticCauses() {
        for (byte[] value : new byte[][]{null, new byte[0], new byte[7], new byte[9], bytes("8000000000000000")}) {
            assertCorrupt(() -> ValueCodec.decodeLong(value));
        }
    }

    @Test
    void vectorsUseExactBigEndianFloatBitsIncludingSignedZeroAndSubnormals() {
        float[] vector = {1.0f, -2.5f, -0.0f, Float.MIN_VALUE, Float.MAX_VALUE};
        String expected = "3f800000c020000080000000000000017f7fffff";
        assertEquals(expected, hex(ValueCodec.encodeVector(vector)));
        assertArrayEquals(vector, ValueCodec.decodeVector(bytes(expected), 5));
        float[] decoded = ValueCodec.decodeVector(bytes(expected), 5);
        decoded[0] = 7;
        assertArrayEquals(vector, ValueCodec.decodeVector(bytes(expected), 5));
    }

    @Test
    void vectorsRejectWrongDimensionsLengthsAndNonfiniteValues() {
        for (int dimension : new int[]{-1, 0, 2, Integer.MAX_VALUE}) {
            assertCorrupt(() -> ValueCodec.decodeVector(new byte[4], dimension));
        }
        assertCorrupt(() -> ValueCodec.decodeVector(null, 1));
        assertCorrupt(() -> ValueCodec.decodeVector(new byte[3], 1));
        assertCorrupt(() -> ValueCodec.decodeVector(new byte[5], 1));
        for (String nonfinite : new String[]{"7f800000", "ff800000", "7fc00000", "7f800001"}) {
            assertCorrupt(() -> ValueCodec.decodeVector(bytes(nonfinite), 1));
        }
        for (float nonfinite : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> ValueCodec.encodeVector(new float[]{nonfinite}));
        }
        assertThrows(IllegalArgumentException.class, () -> ValueCodec.encodeVector(new float[0]));
    }

    @Test
    void nullAndEmptyMetadataHaveDistinctPersistentRepresentations() {
        assertEquals("ffffffff", hex(ValueCodec.encodeMetadata(null)));
        assertEquals("00000000", hex(ValueCodec.encodeMetadata(Map.of())));
        assertNull(ValueCodec.decodeMetadata(bytes("ffffffff")));
        assertEquals(Map.of(), ValueCodec.decodeMetadata(bytes("00000000")));
    }

    @Test
    void metadataIsSortedAndUsesStrictLengthPrefixedUtf8() {
        var first = new LinkedHashMap<String, String>();
        first.put("z", "");
        first.put("a", "é");
        var reverse = new LinkedHashMap<String, String>();
        reverse.put("a", "é");
        reverse.put("z", "");
        String expected = "00000002000000016100000002c3a9000000017a00000000";
        assertEquals(expected, hex(ValueCodec.encodeMetadata(first)));
        assertArrayEquals(ValueCodec.encodeMetadata(first), ValueCodec.encodeMetadata(reverse));
        assertEquals(first, ValueCodec.decodeMetadata(bytes(expected)));
        assertEquals(Map.of("", "a\u0000😀"), ValueCodec.decodeMetadata(ValueCodec.encodeMetadata(Map.of("", "a\u0000😀"))));
    }

    @Test
    void metadataRejectsCorruptionBeforeAllocatingFromUntrustedLengths() {
        String[] corrupt = {"", "000000", "fffffffe", "7fffffff", "00000001", "ffffffff00", "0000000000",
                "000000017fffffff", "00000001ffffffff00000000", "00000001000000017f7fffffff",
                "0000000100000001c300000000", "00000001000000016100000001ff",
                "00000002000000016100000000000000016100000000"};
        for (String encoded : corrupt) {
            assertCorrupt(() -> ValueCodec.decodeMetadata(bytes(encoded)));
        }
        assertCorrupt(() -> ValueCodec.decodeMetadata(null));
        byte[] valid = ValueCodec.encodeMetadata(Map.of("key", "value"));
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);
            assertCorrupt(() -> ValueCodec.decodeMetadata(truncated));
        }
    }

    @Test
    void metadataEncoderRejectsNullEntriesAndMalformedUnicode() {
        var metadata = new LinkedHashMap<String, String>();
        metadata.put(null, "value");
        assertThrows(IllegalArgumentException.class, () -> ValueCodec.encodeMetadata(metadata));
        metadata.clear();
        metadata.put("key", null);
        assertThrows(IllegalArgumentException.class, () -> ValueCodec.encodeMetadata(metadata));
        assertThrows(IllegalArgumentException.class, () -> ValueCodec.encodeMetadata(Map.of("\ud800", "v")));
        assertThrows(IllegalArgumentException.class, () -> ValueCodec.encodeMetadata(Map.of("k", "\udc00")));
    }

    @Test
    void catalogPersistsVersionConfigurationAndCountersWithStableMetricNames() {
        var config = config("x", 2, DistanceMetric.L2, HnswParams.defaults());
        assertEquals(CATALOG, hex(ValueCodec.encodeCatalog(7, config, 3, 2)));
        assertEquals(new ValueCodec.CatalogValue(7, config, 3, 2), ValueCodec.decodeCatalog(bytes(CATALOG)));
        for (DistanceMetric metric : DistanceMetric.values()) {
            var metricConfig = config("collection", 1, metric, new HnswParams(1, 1, 1));
            assertEquals(new ValueCodec.CatalogValue(Long.MAX_VALUE, metricConfig, Long.MAX_VALUE, Long.MAX_VALUE),
                    ValueCodec.decodeCatalog(ValueCodec.encodeCatalog(Long.MAX_VALUE, metricConfig, Long.MAX_VALUE, Long.MAX_VALUE)));
        }
    }

    @Test
    void recoveryDoesNotApplyConfigurableServiceLimits() {
        var config = config("x".repeat(100), 100_000, DistanceMetric.COSINE, new HnswParams(100_000, 100_000, 100_000));
        assertEquals(config, ValueCodec.decodeCatalog(ValueCodec.encodeCatalog(1, config, 0, 0)).config());
        var metadata = new LinkedHashMap<String, String>();
        for (int i = 0; i < 100; i++) metadata.put("key" + i, "v".repeat(2048));
        assertEquals(metadata, ValueCodec.decodeMetadata(ValueCodec.encodeMetadata(metadata)));
    }

    @Test
    void catalogRejectsTruncationTrailingBytesUnknownVersionAndUnknownMetric() {
        byte[] valid = bytes(CATALOG);
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);
            assertCorrupt(() -> ValueCodec.decodeCatalog(truncated));
        }
        assertCorrupt(() -> ValueCodec.decodeCatalog(Arrays.copyOf(valid, valid.length + 1)));
        assertCorrupt(() -> ValueCodec.decodeCatalog(null));
        assertCorrupt(() -> ValueCodec.decodeCatalog(withInt(valid, 0, 2)));
        assertCorrupt(() -> ValueCodec.decodeCatalog(withInt(valid, 12, Integer.MAX_VALUE)));
        byte[] invalidUtf8 = valid.clone();
        invalidUtf8[16] = (byte) 0xff;
        assertCorrupt(() -> ValueCodec.decodeCatalog(invalidUtf8));
        byte[] unknownMetric = valid.clone();
        unknownMetric[25] = 'X';
        assertCorrupt(() -> ValueCodec.decodeCatalog(unknownMetric));
    }

    @Test
    void catalogRejectsImpossibleConfigurationAndCounters() {
        byte[] valid = bytes(CATALOG);
        for (int offset : new int[]{17, 27, 31, 35}) {
            assertCorrupt(() -> ValueCodec.decodeCatalog(withInt(valid, offset, 0)));
            assertCorrupt(() -> ValueCodec.decodeCatalog(withInt(valid, offset, -1)));
        }
        for (int offset : new int[]{4, 39, 47}) {
            byte[] negative = valid.clone();
            ByteBuffer.wrap(negative).putLong(offset, -1);
            assertCorrupt(() -> ValueCodec.decodeCatalog(negative));
        }
        byte[] excessiveCount = valid.clone();
        ByteBuffer.wrap(excessiveCount).putLong(47, 4);
        assertCorrupt(() -> ValueCodec.decodeCatalog(excessiveCount));
        var good = config("x", 2, DistanceMetric.L2, HnswParams.defaults());
        assertThrows(IllegalArgumentException.class, () -> ValueCodec.encodeCatalog(-1, good, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ValueCodec.encodeCatalog(0, good, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> ValueCodec.encodeCatalog(0, good, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> ValueCodec.encodeCatalog(0, good, 0, 1));
        for (CollectionConfig invalid : new CollectionConfig[]{null, config(null, 1, DistanceMetric.L2, HnswParams.defaults()),
                config("", 1, DistanceMetric.L2, HnswParams.defaults()), config("x", 0, DistanceMetric.L2, HnswParams.defaults()),
                config("x", 1, null, HnswParams.defaults()), config("x", 1, DistanceMetric.L2, null),
                config("x", 1, DistanceMetric.L2, new HnswParams(0, 1, 1))}) {
            assertThrows(IllegalArgumentException.class, () -> ValueCodec.encodeCatalog(0, invalid, 0, 0));
        }
    }

    private static CollectionConfig config(String name, int dimension, DistanceMetric metric, HnswParams params) {
        return new CollectionConfig(name, dimension, metric, params);
    }

    private static byte[] withInt(byte[] bytes, int offset, int value) {
        byte[] copy = bytes.clone();
        ByteBuffer.wrap(copy).putInt(offset, value);
        return copy;
    }

    private static void assertCorrupt(org.junit.jupiter.api.function.Executable operation) {
        VectorDbException exception = assertThrows(VectorDbException.class, operation);
        assertEquals(ErrorCode.INTERNAL, exception.getCode());
        assertNotNull(exception.getCause());
    }

    private static byte[] bytes(String hex) { return HexFormat.of().parseHex(hex); }
    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
}
