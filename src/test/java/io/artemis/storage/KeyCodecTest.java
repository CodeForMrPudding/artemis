package io.artemis.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class KeyCodecTest {
    @Test
    void numericKeyMatchesBigEndianOnDiskLayout() {
        byte[] key = KeyCodec.vectorKey(1, 256);
        assertEquals("00000000000000010000000000000100", HexFormat.of().formatHex(key));
        assertEquals(new KeyCodec.InternalKey(1, 256), KeyCodec.decodeVectorKey(key));
        assertArrayEquals(HexFormat.of().parseHex("0000000000000001"), KeyCodec.collectionPrefix(1));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 127, 128, 255, 256, 65535, 65536, Long.MAX_VALUE})
    void numericKeysRoundtripAndStayWithinTheirCollection(long id) {
        byte[] key = KeyCodec.vectorKey(Long.MAX_VALUE, id);
        assertEquals(new KeyCodec.InternalKey(Long.MAX_VALUE, id), KeyCodec.decodeVectorKey(key));
        byte[] prefix = KeyCodec.collectionPrefix(Long.MAX_VALUE);
        assertTrue(Arrays.compareUnsigned(prefix, key) < 0);
        assertTrue(Arrays.compareUnsigned(key, KeyCodec.prefixUpperBound(prefix)) < 0);
    }

    @Test
    void unsignedLexicalOrderMatchesNumericOrderAcrossByteBoundaries() {
        long[] ids = {0, 1, 127, 128, 255, 256, 65535, 65536, Long.MAX_VALUE};
        for (int i = 1; i < ids.length; i++) {
            assertTrue(Arrays.compareUnsigned(KeyCodec.vectorKey(7, ids[i - 1]), KeyCodec.vectorKey(7, ids[i])) < 0);
        }
        assertTrue(Arrays.compareUnsigned(KeyCodec.vectorKey(255, Long.MAX_VALUE), KeyCodec.vectorKey(256, 0)) < 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "café", "😀", "id/with\u0000separator"})
    void externalKeysUseFullUtf8SuffixWithoutDelimiters(String externalId) {
        byte[] key = KeyCodec.externalKey(12, externalId);
        assertEquals(new KeyCodec.ExternalKey(12, externalId), KeyCodec.decodeExternalKey(key));
    }

    @Test
    void externalKeyMatchesLiteralUtf8Bytes() {
        assertEquals("0000000000000001c3a9", HexFormat.of().formatHex(KeyCodec.externalKey(1, "é")));
        assertArrayEquals(new byte[]{(byte) 0xc3, (byte) 0xa9}, KeyCodec.catalogKey("é"));
        assertEquals("café", KeyCodec.decodeCatalogKey(KeyCodec.catalogKey("café")));
        assertEquals("last_applied_seq", KeyCodec.decodeSystemKey(KeyCodec.systemKey("last_applied_seq")));
    }

    @ParameterizedTest
    @CsvSource({"00,01", "00ff,01", "12ffff,13", "7fffffffffffffff,80"})
    void exclusivePrefixBoundHandlesCarry(String prefix, String bound) {
        byte[] input = HexFormat.of().parseHex(prefix);
        assertEquals(bound, HexFormat.of().formatHex(KeyCodec.prefixUpperBound(input)));
        assertEquals(prefix, HexFormat.of().formatHex(input));
    }

    @Test
    void emptyOrAllFfPrefixHasNoFiniteUpperBound() {
        assertNull(KeyCodec.prefixUpperBound(new byte[0]));
        assertNull(KeyCodec.prefixUpperBound(new byte[]{-1, -1}));
    }

    @Test
    void rejectsNegativeIdsMalformedLengthsAndMalformedUnicode() {
        assertThrows(IllegalArgumentException.class, () -> KeyCodec.collectionPrefix(-1));
        assertThrows(IllegalArgumentException.class, () -> KeyCodec.vectorKey(0, -1));
        assertThrows(IllegalArgumentException.class, () -> KeyCodec.externalKey(-1, "x"));
        for (int length : new int[]{0, 8, 15, 17}) {
            assertThrows(IllegalArgumentException.class, () -> KeyCodec.decodeVectorKey(new byte[length]));
        }
        assertThrows(IllegalArgumentException.class, () -> KeyCodec.decodeVectorKey(HexFormat.of().parseHex("80000000000000000000000000000000")));
        assertThrows(IllegalArgumentException.class, () -> KeyCodec.decodeVectorKey(HexFormat.of().parseHex("00000000000000008000000000000000")));
        assertThrows(IllegalArgumentException.class, () -> KeyCodec.decodeExternalKey(new byte[8]));
        assertThrows(IllegalArgumentException.class, () -> KeyCodec.decodeExternalKey(new byte[7]));
        assertThrows(IllegalArgumentException.class, () -> KeyCodec.decodeExternalKey(HexFormat.of().parseHex("800000000000000061")));
        assertThrows(IllegalArgumentException.class, () -> KeyCodec.decodeCatalogKey(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> KeyCodec.systemKey(""));
        assertThrows(IllegalArgumentException.class, () -> KeyCodec.externalKey(1, ""));
        assertThrows(IllegalArgumentException.class, () -> KeyCodec.externalKey(1, "\uD800"));
        assertThrows(IllegalArgumentException.class, () -> KeyCodec.catalogKey("\uDC00"));
        assertThrows(IllegalArgumentException.class, () -> KeyCodec.decodeCatalogKey(new byte[]{(byte) 0xc3}));
        assertThrows(IllegalArgumentException.class, () -> KeyCodec.decodeSystemKey(new byte[]{(byte) 0xff}));
        assertThrows(IllegalArgumentException.class, () -> KeyCodec.decodeExternalKey(HexFormat.of().parseHex("0000000000000001c3")));
    }
}
