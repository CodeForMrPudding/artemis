package io.artemis.storage;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Namespace key layout from the core design. IDs are nonnegative signed longs,
 * encoded in eight big-endian bytes. Text is strict UTF-8 with no terminator;
 * API naming/size rules belong to RequestValidator, not the storage format.
 */
public final class KeyCodec {
    private KeyCodec() {}

    public record InternalKey(long collectionId, long internalId) {}
    public record ExternalKey(long collectionId, String externalId) {}

    public static byte[] collectionPrefix(long collectionId) {
        requireId(collectionId);
        return ByteBuffer.allocate(Long.BYTES).putLong(collectionId).array();
    }

    /** Used by vectors, metadata and int_to_ext. */
    public static byte[] vectorKey(long collectionId, long internalId) {
        requireId(collectionId);
        requireId(internalId);
        return ByteBuffer.allocate(2 * Long.BYTES).putLong(collectionId).putLong(internalId).array();
    }

    public static InternalKey decodeVectorKey(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (key.length != 2 * Long.BYTES) throw new IllegalArgumentException("Numeric key must be 16 bytes");
        var buffer = ByteBuffer.wrap(key);
        long collection = buffer.getLong(), internal = buffer.getLong();
        requireId(collection);
        requireId(internal);
        return new InternalKey(collection, internal);
    }

    /** Used by ext_to_int; the whole suffix is the external ID, including any embedded NULs. */
    public static byte[] externalKey(long collectionId, String externalId) {
        requireId(collectionId);
        byte[] text = encodeText(externalId);
        return ByteBuffer.allocate(Long.BYTES + text.length).putLong(collectionId).put(text).array();
    }

    public static ExternalKey decodeExternalKey(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (key.length <= Long.BYTES) throw new IllegalArgumentException("External key needs an ID suffix");
        long collection = ByteBuffer.wrap(key).getLong();
        requireId(collection);
        return new ExternalKey(collection, decodeText(ByteBuffer.wrap(key, Long.BYTES, key.length - Long.BYTES)));
    }

    public static byte[] catalogKey(String name) { return encodeText(name); }
    public static String decodeCatalogKey(byte[] key) { return decodeText(ByteBuffer.wrap(key)); }
    public static byte[] systemKey(String name) { return encodeText(name); }
    public static String decodeSystemKey(byte[] key) { return decodeText(ByteBuffer.wrap(key)); }

    /**
     * Shortest exclusive upper bound for all keys starting with prefix.
     * Returns null for an empty/all-FF prefix, which has no finite upper bound.
     * This also handles Long.MAX_VALUE collection prefixes without signed overflow.
     */
    public static byte[] prefixUpperBound(byte[] prefix) {
        Objects.requireNonNull(prefix, "prefix");
        for (int i = prefix.length - 1; i >= 0; i--) {
            if (prefix[i] != (byte) 0xff) {
                byte[] bound = Arrays.copyOf(prefix, i + 1);
                bound[i]++;
                return bound;
            }
        }
        return null;
    }

    private static void requireId(long id) {
        if (id < 0) throw new IllegalArgumentException("IDs must be nonnegative");
    }

    private static byte[] encodeText(String text) {
        Objects.requireNonNull(text, "text");
        if (text.isEmpty()) throw new IllegalArgumentException("Key text must not be empty");
        try {
            var encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Key text must contain valid Unicode", e);
        }
    }

    private static String decodeText(ByteBuffer bytes) {
        if (!bytes.hasRemaining()) throw new IllegalArgumentException("Key text must not be empty");
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(bytes).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Key contains malformed UTF-8", e);
        }
    }
}
