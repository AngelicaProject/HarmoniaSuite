package com.harmoniasuite.domain;

import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntryStatusPolicyTest {

    @Test
    @DisplayName("entry status catalog contains every persisted status exactly once")
    void catalogContainsEveryPersistedStatusExactlyOnce() {
        assertEquals(6, EntryStatusPolicy.ALL_STATUSES.size());
        assertEquals(EntryStatusPolicy.ALL_STATUSES.size(),
                EntryStatusPolicy.ALL_STATUSES.stream().distinct().count());
        assertTrue(EntryStatusPolicy.ALL_STATUSES.stream().allMatch(EntryStatusPolicy::isKnown));
    }

    @Test
    @DisplayName("write status normalizes values and defaults manual edits")
    void writeStatusNormalizesValuesAndDefaultsManualEdits() {
        assertEquals(EntryStatusPolicy.APPROVED, EntryStatusPolicy.forWrite(" APPROVED "));
        assertEquals(EntryStatusPolicy.DEFAULT_MANUAL_STATUS, EntryStatusPolicy.forWrite(null));
        assertThrows(HarmoniaSuiteBadRequestException.class, () -> EntryStatusPolicy.forWrite("future"));
    }

    @Test
    @DisplayName("delta cap excludes stale and ranks review statuses")
    void deltaCapExcludesStaleAndRanksReviewStatuses() {
        assertEquals(EntryStatusPolicy.HUMAN_REVIEWED, EntryStatusPolicy.forDeltaCap(" human_reviewed "));
        assertTrue(EntryStatusPolicy.isWithinDeltaCap(
                EntryStatusPolicy.APPROVED, EntryStatusPolicy.APPROVED));
        assertFalse(EntryStatusPolicy.isWithinDeltaCap(
                EntryStatusPolicy.APPROVED, EntryStatusPolicy.HUMAN_REVIEWED));
        assertTrue(EntryStatusPolicy.isWithinDeltaCap(
                EntryStatusPolicy.STALE, EntryStatusPolicy.UNTRANSLATED));
        assertThrows(HarmoniaSuiteBadRequestException.class,
                () -> EntryStatusPolicy.forDeltaCap(EntryStatusPolicy.STALE));
    }

    @Test
    @DisplayName("translation predicates share the status policy")
    void translationPredicatesShareTheStatusPolicy() {
        assertTrue(EntryStatusPolicy.needsWork(EntryStatusPolicy.UNTRANSLATED, ""));
        assertTrue(EntryStatusPolicy.needsWork(EntryStatusPolicy.STALE, "old"));
        assertFalse(EntryStatusPolicy.needsWork(EntryStatusPolicy.NO_TRANSLATION_REQUIRED, ""));
        assertTrue(EntryStatusPolicy.isTranslated(EntryStatusPolicy.NO_TRANSLATION_REQUIRED, ""));
        assertTrue(EntryStatusPolicy.isTranslated(EntryStatusPolicy.APPROVED, "ok"));
        assertFalse(EntryStatusPolicy.isTranslated(EntryStatusPolicy.STALE, "old"));
        assertEquals(List.of(EntryStatusPolicy.UNTRANSLATED, EntryStatusPolicy.MACHINE_TRANSLATED,
                EntryStatusPolicy.NO_TRANSLATION_REQUIRED, EntryStatusPolicy.HUMAN_REVIEWED,
                EntryStatusPolicy.APPROVED), EntryStatusPolicy.DELTA_CAP_STATUSES);
    }
}
