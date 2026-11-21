package io.artemis.api.error;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class VectorDbExceptionTest {
    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void preservesCodeAndMessageForStatusMapping(ErrorCode code) {
        var failure = new VectorDbException(code, "operation failed");
        assertEquals(code, failure.getCode());
        assertEquals("operation failed", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void retainsTheOriginalStorageFailure() {
        var cause = new IllegalStateException("storage failure");
        var failure = new VectorDbException(ErrorCode.INTERNAL, "write failed", cause);
        assertSame(cause, failure.getCause());
        assertEquals(ErrorCode.INTERNAL, failure.getCode());
    }

    @Test
    void rejectsAnExceptionWithoutAStatusCode() {
        assertThrows(NullPointerException.class, () -> new VectorDbException(null, "failed"));
    }
}
