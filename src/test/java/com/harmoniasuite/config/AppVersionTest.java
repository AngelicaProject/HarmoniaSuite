package com.harmoniasuite.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppVersionTest {

    @Test
    @DisplayName("built version passes through as is")
    void resolvedVersionPassesThrough() {
        assertEquals("1.0.0", AppVersion.resolve("1.0.0", "dev"));
    }

    @Test
    @DisplayName("unresolved placeholder gives fallback value")
    void unresolvedPlaceholderFallsBack() {
        assertEquals("dev", AppVersion.resolve("@project.version@", "dev"));
        assertEquals("", AppVersion.resolve("@maven.build.timestamp@", ""));
    }

    @Test
    @DisplayName("blank value gives fallback value")
    void blankValueFallsBack() {
        assertEquals("dev", AppVersion.resolve(null, "dev"));
        assertEquals("dev", AppVersion.resolve("  ", "dev"));
    }
}
