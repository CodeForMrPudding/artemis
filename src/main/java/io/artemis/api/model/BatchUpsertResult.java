package io.artemis.api.model;

public record BatchUpsertResult(int created, int updated, long sequence) {}
