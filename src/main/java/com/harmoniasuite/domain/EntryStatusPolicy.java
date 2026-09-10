package com.harmoniasuite.domain;

import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Centralizes the entry status vocabulary and its write/filter rules. */
public final class EntryStatusPolicy {

    public static final String DEFAULT_MANUAL_STATUS = "human_reviewed";

    private static final List<String> WRITABLE = List.of(
            "untranslated", "machine_translated", "no_translation_required", "stale",
            "human_reviewed", "approved");

    private static final List<String> FILTERABLE = List.of(
            "untranslated", "machine_translated", "no_translation_required", "stale",
            "human_reviewed", "approved");

    private EntryStatusPolicy() {
    }

    public static List<String> parseFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> statuses = new ArrayList<>();
        for (String part : raw.split(",")) {
            String status = normalize(part);
            if (status.isEmpty()) {
                continue;
            }
            if (!FILTERABLE.contains(status)) {
                throw new HarmoniaSuiteBadRequestException("Unknown status: " + part.trim());
            }
            if (!statuses.contains(status)) {
                statuses.add(status);
            }
        }
        return List.copyOf(statuses);
    }

    public static String forWrite(String raw) {
        String status = raw == null || raw.isBlank() ? DEFAULT_MANUAL_STATUS : normalize(raw);
        if (!WRITABLE.contains(status)) {
            throw new HarmoniaSuiteBadRequestException("Unknown status: " + raw);
        }
        return status;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
