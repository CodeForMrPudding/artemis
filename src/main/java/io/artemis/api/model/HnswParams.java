package io.artemis.api.model;

public record HnswParams(int m, int efConstruction, int efSearch) {
    public static HnswParams defaults() {
        return new HnswParams(16, 200, 50);
    }
}
