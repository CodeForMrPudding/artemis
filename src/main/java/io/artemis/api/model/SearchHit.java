package io.artemis.api.model;

import java.util.Map;

/** Smaller distance is closer; unrequested metadata and vector may be null. */
public record SearchHit(String id, float distance, Map<String, String> metadata, float[] vector) {}
