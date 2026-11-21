package io.artemis.command;
import io.artemis.api.model.DeleteResult;
public record DeleteCmd(String collection, String id) implements Command<DeleteResult> {}
