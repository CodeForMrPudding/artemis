package io.artemis.command;

/** Deterministic mutation payload; all mutable input is owned by the command. */
public sealed interface Command<R> permits CreateCollectionCmd, DropCollectionCmd, UpsertBatchCmd, DeleteCmd {}
