package com.harmoniasuite.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UpdateExceptionTest {

    @Test
    @DisplayName("Update error exposes a stable code and safe user message")
    void exposesStableCodeAndSafeMessage() {
        RuntimeException cause = new RuntimeException("fatal: https://user:pass@example.com");
        UpdateException error = new UpdateException(UpdateErrorCode.BUILD_FAILED, cause);

        assertEquals("build_failed", error.code());
        assertEquals("Сборка новой версии завершилась ошибкой", error.getMessage());
        assertEquals("Сборка новой версии завершилась ошибкой: "
                + "fatal: https://***@example.com", error.diagnosticMessage());
        assertSame(cause, error.getCause());
    }

    @Test
    @DisplayName("All update errors have stable codes and user messages")
    void allErrorsHaveStableContracts() {
        for (UpdateErrorCode errorCode : UpdateErrorCode.values()) {
            assertNotNull(errorCode);
            assertFalse(errorCode.code().isBlank());
            assertFalse(errorCode.userMessage().isBlank());
        }
    }
}
