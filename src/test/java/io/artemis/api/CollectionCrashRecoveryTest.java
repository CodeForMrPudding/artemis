package io.artemis.api;

import io.artemis.api.model.VectorRecord;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CollectionCrashRecoveryTest {
    @TempDir Path directory;

    @Test
    void acknowledgedAtomicBatchesSurviveForcibleProcessTermination() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(java, "-cp", System.getProperty("artemis.test.classpath"), CrashWriter.class.getName(), directory.resolve("db").toString())
                .redirectError(directory.resolve("child-error.log").toFile()).start();
        int acknowledged;
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            var firstAcks = CompletableFuture.supplyAsync(() -> {
                try {
                    int last = -1;
                    while (last < 9) {
                        String line = reader.readLine();
                        if (line == null) throw new IllegalStateException("Child exited before acknowledgments");
                        if (line.startsWith("ACK ")) last = Integer.parseInt(line.split(" ")[1]);
                    }
                    return last;
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
            acknowledged = firstAcks.get(20, TimeUnit.SECONDS);
        } finally {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        }
        try (var context = ApplicationContext.run(Map.of("vectordb.enabled", true, "vectordb.data-dir", directory.resolve("db").toString()), "test")) {
            var service = context.getBean(CollectionService.class);
            for (int i = 0; i <= acknowledged; i++) {
                VectorRecord a = service.get("crash", "a" + i).orElseThrow();
                VectorRecord b = service.get("crash", "b" + i).orElseThrow();
                assertArrayEquals(new float[]{i, 1}, a.vector());
                assertArrayEquals(new float[]{i, 2}, b.vector());
            }
            long count = service.getCollection("crash").vectorCount();
            assertEquals(0, count % 2);
            for (int i = acknowledged + 1; i < count / 2; i++) {
                assertTrue(service.get("crash", "a" + i).isPresent());
                assertTrue(service.get("crash", "b" + i).isPresent());
            }
        }
    }
}
