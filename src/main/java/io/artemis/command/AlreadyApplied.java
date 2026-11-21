package io.artemis.command;
/** Replay acknowledgment only; no mutation or original result reconstruction occurs. */
public record AlreadyApplied<R>(long sequence) implements ApplyOutcome<R> {}
