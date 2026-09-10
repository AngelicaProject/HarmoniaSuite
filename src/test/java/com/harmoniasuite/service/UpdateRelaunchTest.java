package com.harmoniasuite.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UpdateRelaunchTest {

    @Test
    @DisplayName("Windows classpath separators still identify development mode")
    void detectsWindowsDevelopmentClasspath() {
        assertTrue(UpdateRelaunch.launchMode("C:\\repo\\target\\classes;C:\\lib\\app.jar").equals("dev"));
    }

    @Test
    @DisplayName("Windows fallback relaunch uses a UTF-16 script without a shell wrapper")
    void windowsFallbackUsesVbs() throws Exception {
        Assumptions.assumeTrue(UpdateRelaunch.isWindows());
        Path script = UpdateRelaunch.writeRelaunch(List.of(
                "C:\\Program Files\\Harmonia\\java.exe",
                "--value=one&two%PATH%",
                "тест"));
        try {
            byte[] raw = Files.readAllBytes(script);
            assertTrue(raw.length > 2 && raw[0] == (byte) 0xFF && raw[1] == (byte) 0xFE);
            String body = new String(raw, StandardCharsets.UTF_16LE);
            assertTrue(body.contains("sh.Run"));
            assertTrue(body.contains("one&two%PATH%"));
            assertFalse(body.contains("cmd /c"));
        } finally {
            Files.deleteIfExists(script);
        }
    }
}
