import io.artemis.api.CollectionService;
import io.artemis.api.error.VectorDbException;
import io.artemis.api.model.*;
import io.micronaut.context.ApplicationContext;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Run through demo/demo.sh using JDK 21. Coordinates make exact ranking easy to inspect. */
public class CollectionApiDemo {
    public static void main(String[] args) {
        if (args.length != 2 || !List.of("write", "recover").contains(args[0])) {
            throw new IllegalArgumentException("Expected <write|recover> <data-directory>");
        }
        try (var context = ApplicationContext.run(Map.of("vectordb.enabled", true, "vectordb.data-dir", args[1]))) {
            var collections = context.getBean(CollectionService.class);
            if (args[0].equals("write")) {
                System.out.println("Create a collection: places, dimension=2, metric=L2 (squared distance)");
                collections.createCollection(new CollectionConfig("places", 2, DistanceMetric.L2, HnswParams.defaults()));
                var inserted = collections.upsertBatch("places", List.of(
                        point("east", 1, 0), point("north", 0, 1), point("northeast", 1, 1)));
                System.out.printf("Insert batch: created=%d, updated=%d, sequence=%d%n", inserted.created(), inserted.updated(), inserted.sequence());

                System.out.println("\nSearch nearest to [0.9, 0.1]:");
                search(collections);

                var updated = collections.upsert("places", point("east", 0.95f, 0.05f));
                System.out.printf("%nUpdate east: created=%s, sequence=%d%n", updated.created(), updated.sequence());
                var stored = collections.get("places", "east").orElseThrow();
                System.out.println("Get east: " + Arrays.toString(stored.vector()) + " " + stored.metadata());

                var deleted = collections.delete("places", "north");
                System.out.printf("Delete north: existed=%s, sequence=%d%n", deleted.existed(), deleted.sequence());

                try {
                    collections.upsert("places", new VectorRecord("bad", new float[]{1, 2, 3}, null));
                    throw new IllegalStateException("Dimension validation did not reject the vector");
                } catch (VectorDbException expected) {
                    System.out.println("Reject a 3D vector: " + expected.getCode());
                }
                System.out.println("Closing the database and ending this process.");
            } else {
                System.out.printf("%nRestarted in a NEW process: recovered places with %d vectors%n",
                        collections.getCollection("places").vectorCount());
                System.out.println("Deleted north is absent: " + collections.get("places", "north").isEmpty());
                System.out.println("Search nearest to [0.9, 0.1] after recovery:");
                search(collections);
            }
        }
    }

    private static VectorRecord point(String id, float x, float y) {
        return new VectorRecord(id, new float[]{x, y}, Map.of("label", id));
    }

    private static void search(CollectionService collections) {
        var hits = collections.search(new SearchRequest("places", new float[]{0.9f, 0.1f}, 3, null, true, true));
        for (var hit : hits) {
            System.out.printf(Locale.ROOT, "  %-10s distance=%.4f vector=%s metadata=%s%n",
                    hit.id(), hit.distance(), Arrays.toString(hit.vector()), hit.metadata());
        }
    }
}
