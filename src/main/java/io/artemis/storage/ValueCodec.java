package io.artemis.storage;

import io.artemis.api.error.ErrorCode;
import io.artemis.api.error.VectorDbException;
import io.artemis.api.model.CollectionConfig;
import io.artemis.api.model.DistanceMetric;
import io.artemis.api.model.HnswParams;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Version-1 value formats use big-endian primitives and strict, int-length-prefixed UTF-8.
 * Storage validation is independent of configurable request limits, so lowering a limit
 * cannot make previously persisted data unreadable.
 */
public final class ValueCodec {
    private static final int CATALOG_VERSION = 1;
    private static final int CATALOG_FIXED_BYTES = 52;

    private ValueCodec() {}

    public record CatalogValue(long id, CollectionConfig config, long nextInternalId, long vectorCount) {}

    public static byte[] encodeLong(long value) {
        requireNonnegative(value, "value");
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    public static long decodeLong(byte[] bytes) {
        return decode("long", () -> {
            ByteBuffer input = input(bytes);
            require(input.remaining() == Long.BYTES, "Long value must be exactly eight bytes");
            long value = input.getLong();
            requireNonnegative(value, "value");
            return value;
        });
    }

    /** Raw finite float32 values, with no header, normalization, or loss of signed zero. */
    public static byte[] encodeVector(float[] vector) {
        require(vector != null && vector.length > 0, "Vector must not be null or empty");
        require(vector.length <= Integer.MAX_VALUE / Float.BYTES, "Vector is too large to encode");
        ByteBuffer output = ByteBuffer.allocate(vector.length * Float.BYTES);
        for (float value : vector) {
            require(Float.isFinite(value), "Vector contains a nonfinite value");
            output.putFloat(value);
        }
        return output.array();
    }

    public static float[] decodeVector(byte[] bytes, int dimension) {
        return decode("vector", () -> {
            ByteBuffer input = input(bytes);
            require(dimension > 0, "Vector dimension must be positive");
            require((long) dimension * Float.BYTES == input.remaining(), "Vector byte length does not match dimension");
            float[] vector = new float[dimension];
            for (int i = 0; i < dimension; i++) {
                vector[i] = input.getFloat();
                require(Float.isFinite(vector[i]), "Stored vector contains a nonfinite value");
            }
            return vector;
        });
    }

    /** A count of -1 represents null; zero represents an empty map. Keys sort by String order. */
    public static byte[] encodeMetadata(Map<String, String> metadata) {
        if (metadata == null) return ByteBuffer.allocate(Integer.BYTES).putInt(-1).array();
        var sorted = new TreeMap<String, String>();
        for (var entry : metadata.entrySet()) {
            require(entry.getKey() != null && entry.getValue() != null, "Metadata keys and values must not be null");
            sorted.put(entry.getKey(), entry.getValue());
        }
        var entries = new ArrayList<EncodedEntry>();
        int size = Integer.BYTES;
        for (var entry : sorted.entrySet()) {
            byte[] key = encodeText(entry.getKey());
            byte[] value = encodeText(entry.getValue());
            size = addSize(size, Integer.BYTES, key.length, Integer.BYTES, value.length);
            entries.add(new EncodedEntry(key, value));
        }
        ByteBuffer output = ByteBuffer.allocate(size).putInt(entries.size());
        for (var entry : entries) {
            putText(output, entry.key());
            putText(output, entry.value());
        }
        return output.array();
    }

    public static Map<String, String> decodeMetadata(byte[] bytes) {
        return decode("metadata", () -> {
            ByteBuffer input = input(bytes);
            int count = input.getInt();
            if (count == -1) {
                requireExhausted(input);
                return null;
            }
            // Even two empty strings require two length fields; check before allocating entries.
            require(count >= 0 && count <= input.remaining() / (2 * Integer.BYTES), "Invalid metadata entry count");
            var metadata = new LinkedHashMap<String, String>();
            for (int i = 0; i < count; i++) {
                String key = readText(input);
                String value = readText(input);
                require(metadata.putIfAbsent(key, value) == null, "Duplicate metadata key");
            }
            requireExhausted(input);
            return Collections.unmodifiableMap(metadata);
        });
    }

    /** Version, id, name, dimension, metric name, HNSW parameters, next id, vector count. */
    public static byte[] encodeCatalog(long id, CollectionConfig config, long nextInternalId, long vectorCount) {
        validateCatalog(id, config, nextInternalId, vectorCount);
        byte[] name = encodeText(config.name());
        byte[] metric = encodeText(config.metric().name());
        ByteBuffer output = ByteBuffer.allocate(addSize(CATALOG_FIXED_BYTES, name.length, metric.length));
        output.putInt(CATALOG_VERSION).putLong(id);
        putText(output, name);
        output.putInt(config.dimension());
        putText(output, metric);
        output.putInt(config.hnsw().m()).putInt(config.hnsw().efConstruction()).putInt(config.hnsw().efSearch());
        output.putLong(nextInternalId).putLong(vectorCount);
        return output.array();
    }

    public static CatalogValue decodeCatalog(byte[] bytes) {
        return decode("catalog", () -> {
            ByteBuffer input = input(bytes);
            int version = input.getInt();
            require(version == CATALOG_VERSION, "Unsupported catalog version: " + version);
            long id = input.getLong();
            String name = readText(input);
            int dimension = input.getInt();
            DistanceMetric metric = DistanceMetric.valueOf(readText(input));
            HnswParams params = new HnswParams(input.getInt(), input.getInt(), input.getInt());
            long nextInternalId = input.getLong();
            long vectorCount = input.getLong();
            requireExhausted(input);
            CollectionConfig config = new CollectionConfig(name, dimension, metric, params);
            validateCatalog(id, config, nextInternalId, vectorCount);
            return new CatalogValue(id, config, nextInternalId, vectorCount);
        });
    }

    private static void validateCatalog(long id, CollectionConfig config, long nextInternalId, long vectorCount) {
        requireNonnegative(id, "collection id");
        requireNonnegative(nextInternalId, "next internal id");
        requireNonnegative(vectorCount, "vector count");
        require(vectorCount <= nextInternalId, "Vector count exceeds allocated internal IDs");
        require(config != null, "Collection config must not be null");
        require(config.name() != null && !config.name().isEmpty(), "Collection name must not be null or empty");
        require(config.dimension() > 0, "Collection dimension must be positive");
        require(config.metric() != null, "Collection metric must not be null");
        HnswParams params = config.hnsw();
        require(params != null && params.m() > 0 && params.efConstruction() > 0 && params.efSearch() > 0,
                "HNSW parameters must be positive");
    }

    private static ByteBuffer input(byte[] bytes) {
        require(bytes != null, "Stored value must not be null");
        return ByteBuffer.wrap(bytes);
    }

    private static void requireExhausted(ByteBuffer input) {
        require(!input.hasRemaining(), "Stored value contains trailing bytes");
    }

    private static void requireNonnegative(long value, String field) {
        require(value >= 0, field + " must be nonnegative");
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }

    private static byte[] encodeText(String text) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Text contains invalid Unicode", e);
        }
    }

    private static void putText(ByteBuffer output, byte[] text) {
        output.putInt(text.length).put(text);
    }

    private static String readText(ByteBuffer input) {
        int length = input.getInt();
        require(length >= 0 && length <= input.remaining(), "Invalid UTF-8 text length: " + length);
        ByteBuffer text = input.slice();
        text.limit(length);
        input.position(input.position() + length);
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(text).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Stored text contains malformed UTF-8", e);
        }
    }

    private static int addSize(int size, int... lengths) {
        for (int length : lengths) {
            require(length <= Integer.MAX_VALUE - size, "Value is too large to encode");
            size += length;
        }
        return size;
    }

    private static <T> T decode(String type, Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RuntimeException e) {
            throw new VectorDbException(ErrorCode.INTERNAL, "Malformed stored " + type + ": " + e.getMessage(), e);
        }
    }

    private record EncodedEntry(byte[] key, byte[] value) {}
}
