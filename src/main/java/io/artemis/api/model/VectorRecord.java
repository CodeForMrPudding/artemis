package io.artemis.api.model;

import java.util.Map;

/** A shallow carrier; the service must copy mutable inputs before preparing commands. */
public record VectorRecord(String id, float[] vector, Map<String, String> metadata) {}
