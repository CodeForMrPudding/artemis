package io.artemis.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelTest {
    @Test
    void buildersAllowDocumentedOptionalSearchFieldsToBeOmitted() {
        var config = CollectionConfig.builder()
                .name("documents").dimension(2).metric(DistanceMetric.COSINE)
                .hnsw(HnswParams.defaults()).build();
        var request = SearchRequest.builder()
                .collection(config.name()).query(new float[]{1, 0}).k(10).build();

        assertEquals(new HnswParams(16, 200, 50), config.hnsw());
        assertNull(request.efSearch());
        assertFalse(request.includeMetadata());
        assertFalse(request.includeVector());
    }
}

