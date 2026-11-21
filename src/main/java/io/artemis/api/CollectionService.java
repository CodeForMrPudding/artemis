package io.artemis.api;

import io.artemis.api.model.*;
import java.util.List;
import java.util.Optional;

/** Stable in-process entry point for the single-node core. */
public interface CollectionService {
    CollectionInfo createCollection(CollectionConfig config);
    void dropCollection(String name);
    CollectionInfo getCollection(String name);
    List<CollectionInfo> listCollections();
    UpsertResult upsert(String collection, VectorRecord record);
    BatchUpsertResult upsertBatch(String collection, List<VectorRecord> records);
    Optional<VectorRecord> get(String collection, String id);
    DeleteResult delete(String collection, String id);
    List<SearchHit> search(SearchRequest request);
}
