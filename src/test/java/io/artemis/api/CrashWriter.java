package io.artemis.api;

import io.artemis.api.model.*;
import io.micronaut.context.ApplicationContext;
import java.util.*;

/** Child-process fixture: only emit ACK after both rows of a batch are durable and indexed. */
public final class CrashWriter {
    public static void main(String[] args) {
        try (var context = ApplicationContext.run(Map.of("vectordb.enabled", true, "vectordb.data-dir", args[0]), "test")) {
            var service = context.getBean(CollectionService.class);
            service.createCollection(new CollectionConfig("crash", 2, DistanceMetric.L2, HnswParams.defaults()));
            for (int i = 0; ; i++) {
                var result = service.upsertBatch("crash", List.of(
                        new VectorRecord("a" + i, new float[]{i, 1}, null),
                        new VectorRecord("b" + i, new float[]{i, 2}, null)));
                System.out.println("ACK " + i + " " + result.sequence());
                System.out.flush();
            }
        }
    }
}
