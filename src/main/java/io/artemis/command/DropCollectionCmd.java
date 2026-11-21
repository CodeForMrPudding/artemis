package io.artemis.command;
public record DropCollectionCmd(String name) implements Command<Void> {}
