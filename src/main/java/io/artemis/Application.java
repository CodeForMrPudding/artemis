package io.artemis;

import io.artemis.api.error.VectorDbException;
import io.artemis.storage.spike.RocksDbSpike;
import io.micronaut.context.ApplicationContext;

import java.nio.file.Path;
import java.util.Set;
import java.util.Map;

/** Serverless integration checks for disposable databases. The library API is CollectionService. */
public final class Application {
    private Application() {}

    public static void main(String[] args) {
        if (args.length != 2 || !Set.of("write", "read", "roundtrip", "api-write", "api-read").contains(args[0])) {
            System.err.println("Usage: artemis-spike <write|read|roundtrip|api-write|api-read> <disposable-db-directory>");
            System.exit(2);
            return;
        }
        if (args[0].startsWith("api-")) {
            ApiSmoke.run(args[0], Path.of(args[1]));
            return;
        }
        try (var context = ApplicationContext.run(Map.of("vectordb.enabled", false))) {
            var spike = context.getBean(RocksDbSpike.class);
            var directory = Path.of(args[1]);
            switch (args[0]) {
                case "write" -> spike.write(directory);
                case "read" -> spike.verify(directory);
                case "roundtrip" -> spike.roundtrip(directory);
                default -> throw new IllegalStateException("Unexpected mode: " + args[0]);
            }
            System.out.println("ROCKSDB_SPIKE_OK " + args[0]);
        } catch (VectorDbException e) {
            System.err.println(e.getCode() + ": " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
