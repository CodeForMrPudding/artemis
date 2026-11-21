package io.artemis.command;

import io.artemis.api.RequestValidator;
import io.artemis.api.error.ErrorCode;
import io.artemis.api.model.*;
import io.artemis.config.*;
import io.artemis.storage.*;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CommandApplierTest {
    @Test
    void explicitSequenceReplayIsIgnoredAndRejectedSequencesAreDurable() {
        try (var context = ApplicationContext.run(Map.of("vectordb.enabled", false));
             var lifecycle = new EngineLifecycle(new InMemoryStorageEngine(), context.getBean(RequestValidator.class), context.getBean(WriterConfig.class))) {
            lifecycle.start();
            var applier = lifecycle.applier();
            var create = new CreateCollectionCmd(new CollectionConfig("c", 2, DistanceMetric.L2, HnswParams.defaults()));
            assertInstanceOf(Applied.class, applier.apply(1, create));
            assertInstanceOf(AlreadyApplied.class, applier.apply(1, create));
            var rejected = assertInstanceOf(Rejected.class, applier.apply(2, create));
            assertEquals(ErrorCode.ALREADY_EXISTS, rejected.code());
            assertEquals(2, applier.lastAppliedSeq());
            assertInstanceOf(AlreadyApplied.class, applier.apply(2, create));
            assertInstanceOf(Rejected.class, applier.apply(3, new DeleteCmd("missing", "x")));
            assertEquals(3, applier.lastAppliedSeq());
        }
    }

    @Test
    void twoEnginesProduceIdenticalDurableBytesForTheSameCommandStream() {
        try (var context = ApplicationContext.run(Map.of("vectordb.enabled", false));
             var a = new InMemoryStorageEngine(); var b = new InMemoryStorageEngine();
             var left = new EngineLifecycle(a, context.getBean(RequestValidator.class), context.getBean(WriterConfig.class));
             var right = new EngineLifecycle(b, context.getBean(RequestValidator.class), context.getBean(WriterConfig.class))) {
            left.start(); right.start();
            List<Command<?>> commands = List.of(
                    new CreateCollectionCmd(new CollectionConfig("c", 2, DistanceMetric.COSINE, HnswParams.defaults())),
                    new UpsertBatchCmd("c", List.of(new VectorRecord("x", new float[]{3, 4}, Map.of("b", "2", "a", "1")))),
                    new DeleteCmd("c", "absent"), new DropCollectionCmd("absent"),
                    new UpsertBatchCmd("c", List.of(new VectorRecord("x", new float[]{1, 0}, null))));
            for (int i = 0; i < commands.size(); i++) {
                var first = left.applier().apply(i + 1, commands.get(i));
                var second = right.applier().apply(i + 1, commands.get(i));
                assertEquals(first, second);
            }
            for (var namespace : Namespace.values()) {
                try (var first = a.scan(namespace, null, null); var second = b.scan(namespace, null, null)) {
                    while (first.hasNext()) {
                        assertTrue(second.hasNext());
                        var x = first.next(); var y = second.next();
                        assertArrayEquals(x.key(), y.key());
                        assertArrayEquals(x.value(), y.value());
                    }
                    assertFalse(second.hasNext());
                }
            }
        }
    }

    @Test
    void commandsOwnVectorsMetadataAndRecordLists() {
        float[] vector = {1, 2};
        var map = new HashMap<>(Map.of("a", "b"));
        var records = new ArrayList<>(List.of(new VectorRecord("x", vector, map)));
        var command = new UpsertBatchCmd("c", records);
        vector[0] = 99; map.put("a", "changed"); records.clear();
        assertEquals(1, command.records().size());
        assertArrayEquals(new float[]{1, 2}, command.records().getFirst().vector());
        assertEquals(Map.of("a", "b"), command.records().getFirst().metadata());
        command.records().getFirst().vector()[0] = 88;
        assertArrayEquals(new float[]{1, 2}, command.records().getFirst().vector());
    }
}
