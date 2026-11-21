package io.artemis.api.model;

public record UpsertResult(boolean created, long sequence) {}
