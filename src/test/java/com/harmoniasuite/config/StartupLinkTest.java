package com.harmoniasuite.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupLinkTest {

    @Test
    @DisplayName("link is built from the actual port")
    void urlUsesActualPort() {
        assertEquals("http://127.0.0.1:18765", StartupLink.url("18765"));
    }

    @Test
    @DisplayName("default link is 8765")
    void urlDefaultsTo8765() {
        assertEquals("http://127.0.0.1:8765", StartupLink.url(null));
        assertEquals("http://127.0.0.1:8765", StartupLink.url(""));
    }

    @Test
    @DisplayName("tab opens only on install, not on relaunch")
    void openOnlyForFreshInstall() {
        assertTrue(StartupLink.shouldOpen("C:/exa/app.exe", null));
        assertTrue(StartupLink.shouldOpen("C:/exa/app.exe", ""));
        assertFalse(StartupLink.shouldOpen("C:/exa/app.exe", "1"));
        assertFalse(StartupLink.shouldOpen(null, null));
    }
}
