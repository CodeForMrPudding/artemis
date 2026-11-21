package io.artemis;

import io.artemis.api.CollectionService;
import io.artemis.api.error.*;
import io.artemis.api.model.*;
import io.micronaut.context.ApplicationContext;
import java.nio.file.Path;
import java.util.*;

/** Executable integration check for JVM/native builds; use only a disposable data directory. */
final class ApiSmoke {
    private ApiSmoke() {}
    static void run(String mode, Path directory) {
        try (var context = ApplicationContext.run(Map.of("vectordb.enabled", true, "vectordb.data-dir", directory.toString()))) {
            var service = context.getBean(CollectionService.class);
            if (mode.equals("api-write")) {
                service.createCollection(new CollectionConfig("smoke", 2, DistanceMetric.COSINE, HnswParams.defaults()));
                service.upsertBatch("smoke", List.of(new VectorRecord("near", new float[]{3, 4}, Map.of("tag", "native")),
                        new VectorRecord("deleted", new float[]{-1, 0}, null)));
                service.delete("smoke", "deleted");
            }
            var record = service.get("smoke", "near").orElseThrow();
            var hits = service.search(new SearchRequest("smoke", new float[]{3, 4}, 5, null, true, true));
            if (service.getCollection("smoke").vectorCount() != 1 || service.get("smoke", "deleted").isPresent()
                    || !Arrays.equals(new float[]{0.6f, 0.8f}, record.vector()) || hits.size() != 1
                    || !hits.getFirst().id().equals("near") || !Map.of("tag", "native").equals(hits.getFirst().metadata())) {
                throw new VectorDbException(ErrorCode.INTERNAL, "Collection API smoke verification failed");
            }
            System.out.println("COLLECTION_API_OK " + mode);
        }
    }
}
