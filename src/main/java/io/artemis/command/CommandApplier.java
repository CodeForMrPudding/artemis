package io.artemis.command;
import java.util.concurrent.CompletableFuture;
public interface CommandApplier {
    <R> CompletableFuture<ApplyOutcome<R>> submit(Command<R> command);
    <R> ApplyOutcome<R> apply(long sequence, Command<R> command);
    long lastAppliedSeq();
}
