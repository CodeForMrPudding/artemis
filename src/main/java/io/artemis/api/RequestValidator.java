package io.artemis.api;

import io.artemis.api.error.ErrorCode;
import io.artemis.api.error.VectorDbException;
import io.artemis.api.model.CollectionConfig;
import io.artemis.api.model.DistanceMetric;
import io.artemis.api.model.SearchRequest;
import io.artemis.api.model.VectorRecord;
import io.artemis.config.LimitsConfig;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deterministic, non-mutating checks shared by the service and command applier.
 * No collection lookup or normalization occurs here. Callers with collection
 * state must additionally call validateDimension and validateVectorForMetric.
 */
@Singleton
@RequiredArgsConstructor
public class RequestValidator {
    private static final Pattern COLLECTION_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    private final LimitsConfig limits;

    public void validateCreateCollection(CollectionConfig config) {
        require(config != null, "Collection config is required");
        validateCollectionName(config.name());
        require(config.dimension() > 0 && config.dimension() <= limits.getMaxDimension(),
                "Dimension is outside the configured limits");
        require(config.metric() != null, "Distance metric is required");
        require(config.hnsw() != null, "HNSW parameters are required");
        require(config.hnsw().m() > 0 && config.hnsw().efConstruction() > 0 && config.hnsw().efSearch() > 0,
                "HNSW parameters must be positive");
    }

    public void validateCollectionName(String name) {
        require(name != null && !name.isEmpty() && name.length() <= limits.getMaxCollectionNameLength()
                        && COLLECTION_NAME.matcher(name).matches(),
                "Collection name must use letters, digits, underscores or hyphens within the configured length");
    }

    public void validateId(String id) {
        require(id != null && !id.isEmpty(), "Vector id is required");
        requireUtf8Within(id, limits.getMaxIdBytes(), "Vector id");
    }

    /** Shape checks only; every record is checked, including earlier duplicate IDs. */
    public void validateUpsert(String collection, List<VectorRecord> records) {
        validateCollectionName(collection);
        require(records != null && !records.isEmpty() && records.size() <= limits.getMaxBatchSize(),
                "Batch size is outside the configured limits");
        for (var record : records) {
            require(record != null, "Vector record is required");
            validateId(record.id());
            validateVector(record.vector());
            validateMetadata(record.metadata());
        }
    }

    /** A positive efSearch below k is valid; the query executor will raise it to k. */
    public void validateSearch(SearchRequest request) {
        require(request != null, "Search request is required");
        validateCollectionName(request.collection());
        validateVector(request.query());
        require(request.k() > 0 && request.k() <= limits.getMaxK(), "k is outside the configured limits");
        require(request.efSearch() == null || request.efSearch() > 0, "efSearch must be positive when supplied");
    }

    /** Apply against the collection's authoritative dimension on the writer thread (or read path). */
    public void validateDimension(float[] vector, int dimension) {
        require(dimension > 0 && dimension <= limits.getMaxDimension(), "Invalid collection dimension");
        require(vector != null && vector.length == dimension, "Vector dimension does not match the collection");
    }

    /** Check before normalization; exact nonzero comparison also accepts subnormal floats. */
    public void validateVectorForMetric(float[] vector, DistanceMetric metric) {
        require(metric != null, "Distance metric is required");
        validateVector(vector);
        if (metric == DistanceMetric.COSINE) {
            for (float value : vector) {
                if (value != 0f) {
                    return;
                }
            }
            throw new VectorDbException(ErrorCode.INVALID_ARGUMENT, "Cosine vector must not be all zeros");
        }
    }

    private void validateVector(float[] vector) {
        require(vector != null && vector.length > 0 && vector.length <= limits.getMaxDimension(),
                "Vector length is outside the configured limits");
        for (float value : vector) {
            require(Float.isFinite(value), "Vector values must be finite");
        }
    }

    private void validateMetadata(Map<String, String> metadata) {
        if (metadata == null) {
            return;
        }
        require(metadata.size() <= limits.getMaxMetadataEntries(), "Too many metadata entries");
        // Maps need not iterate identically on every replica. Choose failure order
        // by key without changing the caller's map or its insertion order.
        var entries = new ArrayList<>(metadata.entrySet());
        entries.sort(Map.Entry.comparingByKey(Comparator.nullsFirst(Comparator.naturalOrder())));
        for (var entry : entries) {
            requireUtf8Within(entry.getKey(), limits.getMaxMetadataKeyBytes(), "Metadata key");
            requireUtf8Within(entry.getValue(), limits.getMaxMetadataValueBytes(), "Metadata value");
        }
    }

    private static void requireUtf8Within(String value, int maxBytes, String field) {
        require(value != null, field + " must not be null");
        // A UTF-8 encoding cannot be shorter than the UTF-16 code-unit count.
        require(value.length() <= maxBytes, field + " exceeds the configured UTF-8 byte limit");
        try {
            int length = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value)).remaining();
            require(length <= maxBytes, field + " exceeds the configured UTF-8 byte limit");
        } catch (CharacterCodingException e) {
            throw new VectorDbException(ErrorCode.INVALID_ARGUMENT, field + " must contain valid Unicode", e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new VectorDbException(ErrorCode.INVALID_ARGUMENT, message);
        }
    }
}
