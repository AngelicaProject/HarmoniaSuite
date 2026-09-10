package com.harmoniasuite.domain;

import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Centralizes the entry status vocabulary and its write/filter rules. */
public final class EntryStatusPolicy {

    public static final String UNTRANSLATED = "untranslated";
    public static final String MACHINE_TRANSLATED = "machine_translated";
    public static final String NO_TRANSLATION_REQUIRED = "no_translation_required";
    public static final String STALE = "stale";
    public static final String HUMAN_REVIEWED = "human_reviewed";
    public static final String APPROVED = "approved";

    public static final String DEFAULT_MANUAL_STATUS = HUMAN_REVIEWED;

    /** All statuses accepted by the entry API and persisted in the database. */
    public static final List<String> ALL_STATUSES = List.of(
            UNTRANSLATED, MACHINE_TRANSLATED, NO_TRANSLATION_REQUIRED, STALE,
            HUMAN_REVIEWED, APPROVED);

    /** Statuses that can be used as a delta import cap, ordered by review progress. */
    public static final List<String> DELTA_CAP_STATUSES = List.of(
            UNTRANSLATED, MACHINE_TRANSLATED, NO_TRANSLATION_REQUIRED, HUMAN_REVIEWED, APPROVED);

    public static final Set<String> CONFLICT_STATUSES = Set.of(
            HUMAN_REVIEWED, APPROVED, NO_TRANSLATION_REQUIRED);

    private static final Map<String, Integer> DELTA_RANK = Map.of(
            UNTRANSLATED, 0,
            MACHINE_TRANSLATED, 1,
            NO_TRANSLATION_REQUIRED, 2,
            HUMAN_REVIEWED, 3,
            APPROVED, 4);

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
            if (!isKnown(status)) {
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
        if (!isKnown(status)) {
            throw new HarmoniaSuiteBadRequestException("Unknown status: " + raw);
        }
        return status;
    }

    public static String forDeltaCap(String raw) {
        String status = raw == null || raw.isBlank() ? DEFAULT_MANUAL_STATUS : normalize(raw);
        if (!DELTA_CAP_STATUSES.contains(status)) {
            throw new HarmoniaSuiteBadRequestException("Unknown status: " + raw);
        }
        return status;
    }

    public static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isKnown(String status) {
        return ALL_STATUSES.contains(status);
    }

    public static boolean isStale(String status) {
        return STALE.equals(status);
    }

    public static boolean isNoTranslationRequired(String status) {
        return NO_TRANSLATION_REQUIRED.equals(status);
    }

    public static boolean isWithinDeltaCap(String status, String maxStatus) {
        if (isStale(status)) {
            return true;
        }
        Integer rank = DELTA_RANK.get(status);
        Integer maxRank = DELTA_RANK.get(maxStatus);
        return rank != null && maxRank != null && rank <= maxRank;
    }

    public static boolean isConflict(String status) {
        return CONFLICT_STATUSES.contains(status);
    }

    public static boolean needsWork(String status, String translation) {
        return !isNoTranslationRequired(status)
                && (translation == null || translation.trim().isEmpty() || isStale(status));
    }

    public static boolean isTranslated(String status, String translation) {
        return isNoTranslationRequired(status)
                || (translation != null && !translation.trim().isEmpty() && !isStale(status));
    }
}
