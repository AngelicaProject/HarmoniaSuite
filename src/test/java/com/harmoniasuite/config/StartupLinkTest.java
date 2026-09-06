package com.harmoniasuite.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StartupLinkTest {

    @Test
    @DisplayName("ссылка собирается из фактического порта")
    void urlUsesActualPort() {
        assertEquals("http://127.0.0.1:18765", StartupLink.url("18765"));
    }

    @Test
    @DisplayName("ссылка по умолчанию — 8765")
    void urlDefaultsTo8765() {
        assertEquals("http://127.0.0.1:8765", StartupLink.url(null));
        assertEquals("http://127.0.0.1:8765", StartupLink.url(""));
    }
}
