package com.harmoniasuite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UpdateProcessTest {

    @Test
    @DisplayName("Removes sensitive environment variables from child processes")
    void removesSensitiveEnvironmentVariables() {
        Map<String, String> environment = UpdateProcess.sanitizedEnvironment(Map.of(
                "GEMINI_API_KEY", "secret",
                "JAVA_TOOL_OPTIONS", "-javaagent:secret.jar",
                "SAFE_UPDATE_VALUE", "ok"));

        assertFalse(environment.containsKey("GEMINI_API_KEY"));
        assertFalse(environment.containsKey("JAVA_TOOL_OPTIONS"));
        assertEquals("ok", environment.get("SAFE_UPDATE_VALUE"));
    }
}
