package com.harmoniasuite.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppVersionTest {

    @Test
    @DisplayName("собранная версия проходит как есть")
    void resolvedVersionPassesThrough() {
        assertEquals("1.0.0", AppVersion.resolve("1.0.0", "dev"));
    }

    @Test
    @DisplayName("неподставленный плейсхолдер даёт запасное значение")
    void unresolvedPlaceholderFallsBack() {
        assertEquals("dev", AppVersion.resolve("@project.version@", "dev"));
        assertEquals("", AppVersion.resolve("@maven.build.timestamp@", ""));
    }

    @Test
    @DisplayName("пустое значение даёт запасное значение")
    void blankValueFallsBack() {
        assertEquals("dev", AppVersion.resolve(null, "dev"));
        assertEquals("dev", AppVersion.resolve("  ", "dev"));
    }
}
