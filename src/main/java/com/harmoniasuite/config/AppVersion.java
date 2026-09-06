package com.harmoniasuite.config;

public final class AppVersion {

    private AppVersion() {
    }

    public static String resolve(String raw, String fallback) {
        if (raw == null || raw.isBlank() || raw.contains("@")) {
            return fallback;
        }
        return raw;
    }
}
