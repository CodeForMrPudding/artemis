package io.artemis.storage;

/** Durable namespace names; persist these strings, never enum ordinals. */
public enum Namespace {
    CATALOG("catalog"),
    VECTORS("vectors"),
    METADATA("metadata"),
    EXT_TO_INT("ext_to_int"),
    INT_TO_EXT("int_to_ext"),
    SYSTEM("system");

    private final String persistentName;

    Namespace(String persistentName) {
        this.persistentName = persistentName;
    }

    public String persistentName() {
        return persistentName;
    }
}
