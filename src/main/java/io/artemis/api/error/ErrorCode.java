package io.artemis.api.error;

/** Names match gRPC canonical status codes for the later RPC layer. */
public enum ErrorCode {
    INVALID_ARGUMENT,
    NOT_FOUND,
    ALREADY_EXISTS,
    RESOURCE_EXHAUSTED,
    UNAVAILABLE,
    INTERNAL
}
