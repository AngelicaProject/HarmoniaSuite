package com.harmoniasuite.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScrubSupportTest {

    @Test
    @DisplayName("scrub masks credentials in URLs but keeps ordinary URLs")
    void scrubsUrlCredentials() {
        assertEquals("https://***@example.com/path", ScrubSupport.scrub("https://user:pass@example.com/path"));
        assertEquals("https://example.com/path", ScrubSupport.scrub("https://example.com/path"));
        assertNull(ScrubSupport.scrub(null));
        assertEquals("  ", ScrubSupport.scrub("  "));
    }

    @Test
    @DisplayName("scrub masks the user name in home paths but keeps the rest")
    void scrubsHomeUserName() {
        assertEquals("C:\\Users\\***\\AppData\\app.log",
                ScrubSupport.scrub("C:\\Users\\testuser\\AppData\\app.log"));
        assertEquals("C:/Users/***/repo/pom.xml",
                ScrubSupport.scrub("C:/Users/testuser/repo/pom.xml"));
        assertEquals("/Users/***/repo", ScrubSupport.scrub("/Users/testuser/repo"));
        assertEquals("/home/***/repo", ScrubSupport.scrub("/home/testuser/repo"));
        assertEquals("at C:\\Users\\***\\repo (line 1)",
                ScrubSupport.scrub("at C:\\Users\\John Doe\\repo (line 1)"));
    }

    @Test
    @DisplayName("scrub keeps ordinary paths without a home prefix")
    void keepsOrdinaryPaths() {
        assertEquals("C:\\Program Files\\app\\app.log",
                ScrubSupport.scrub("C:\\Program Files\\app\\app.log"));
        assertEquals("/opt/data/file", ScrubSupport.scrub("/opt/data/file"));
    }
}
