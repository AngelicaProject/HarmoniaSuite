package com.harmoniasuite.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntryIdsTest {

    @Test
    @DisplayName("cell id is stable and has c_ prefix")
    void cellIdIsStableAndPrefixed() {
        String first = EntryIds.ofCell("pack-one.csv", "10", 2);
        assertEquals(first, EntryIds.ofCell("pack-one.csv", "10", 2));
        assertTrue(first.startsWith("c_"));
        assertEquals(18, first.length());
        assertTrue(first.substring(2).matches("[0-9a-f]{16}"));
    }

    @Test
    @DisplayName("different cells give different ids")
    void differentCellsGiveDifferentIds() {
        String base = EntryIds.ofCell("pack-one.csv", "10", 2);
        assertNotEquals(base, EntryIds.ofCell("pack-two.csv", "10", 2));
        assertNotEquals(base, EntryIds.ofCell("pack-one.csv", "11", 2));
        assertNotEquals(base, EntryIds.ofCell("pack-one.csv", "10", 3));
    }

    @Test
    @DisplayName("text does not affect id, only coordinates")
    void textDoesNotAffectId() {
        assertEquals(EntryIds.ofCell("pack-one.csv", "10", 2), EntryIds.ofCell("pack-one.csv", "10", 2));
    }
}
