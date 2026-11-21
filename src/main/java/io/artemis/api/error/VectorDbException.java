package io.artemis.api.error;

import java.util.Objects;

/** A stable public failure code with an optional underlying diagnostic cause. */
public class VectorDbException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final ErrorCode code;

    public VectorDbException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public VectorDbException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ErrorCode getCode() {
        return code;
    }
}
