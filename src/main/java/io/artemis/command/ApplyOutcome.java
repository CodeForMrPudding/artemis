package io.artemis.command;
public sealed interface ApplyOutcome<R> permits Applied, Rejected, AlreadyApplied {
    long sequence();
}
