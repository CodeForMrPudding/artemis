package io.artemis.command;
public record Applied<R>(long sequence, R result) implements ApplyOutcome<R> {}
