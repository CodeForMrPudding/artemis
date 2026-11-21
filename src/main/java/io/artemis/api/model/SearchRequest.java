package io.artemis.api.model;

import lombok.Builder;

/** Null efSearch selects the collection default; query is a caller-owned array. */
@Builder
public record SearchRequest(
        String collection,
        float[] query,
        int k,
        Integer efSearch,
        boolean includeMetadata,
        boolean includeVector) {}
