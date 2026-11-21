package io.artemis.command;
import io.artemis.api.error.ErrorCode;
public record Rejected<R>(long sequence, ErrorCode code, String message) implements ApplyOutcome<R> {}
