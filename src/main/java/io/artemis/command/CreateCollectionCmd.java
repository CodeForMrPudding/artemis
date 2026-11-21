package io.artemis.command;
import io.artemis.api.model.*;
public record CreateCollectionCmd(CollectionConfig config) implements Command<CollectionInfo> {}
