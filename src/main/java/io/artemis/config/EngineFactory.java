package io.artemis.config;

import io.artemis.catalog.CollectionRegistry;
import io.artemis.command.CommandApplier;
import io.artemis.storage.*;
import io.micronaut.context.annotation.*;
import jakarta.inject.Singleton;
import java.nio.file.Path;

@Factory
@Requires(property = "vectordb.enabled", notEquals = "false")
public class EngineFactory {
    @Singleton
    public StorageEngine storageEngine(@Value("${vectordb.data-dir:./data}") String directory) {
        return new RocksDbStorageEngine(Path.of(directory));
    }
    @Singleton public CollectionRegistry collectionRegistry(EngineLifecycle lifecycle) { return lifecycle.registry(); }
    @Singleton public CommandApplier commandApplier(EngineLifecycle lifecycle) { return lifecycle.applier(); }
}
