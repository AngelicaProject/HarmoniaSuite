package com.harmoniasuite.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvSupportTest {

    private final CsvSupport csv = new CsvSupport();

    @Test
    @DisplayName("CSV round-trip preserves special characters")
    void roundTripPreservesSpecialCharacters(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.csv");
        List<List<String>> rows = new ArrayList<>();
        rows.add(new ArrayList<>(List.of("key", "0", "Name", "Desc")));
        rows.add(new ArrayList<>(List.of("#", "str", "Name", "Desc")));
        rows.add(new ArrayList<>(List.of("0", "0", "0", "0")));
        rows.add(new ArrayList<>(List.of("Int32", "String", "String", "UInt16")));
        rows.add(new ArrayList<>(List.of("1", "Plain", "With, comma", "With \"quotes\"")));
        rows.add(new ArrayList<>(List.of("2", "Multi\nline", "Привет, мир", "")));
        csv.writeRowsAtomic(file, rows);
        assertEquals(rows, csv.readRows(file));
    }

    @Test
    @DisplayName("String column lookup by type row")
    void stringColumnsDetectsStringTypeRow() {
        List<List<String>> rows = new ArrayList<>();
        rows.add(new ArrayList<>(List.of("a", "b")));
        rows.add(new ArrayList<>(List.of("a", "b")));
        rows.add(new ArrayList<>(List.of("a", "b")));
        rows.add(new ArrayList<>(List.of("Int32", " sTring ", "UInt16")));
        assertEquals(List.of(1), csv.stringColumns(rows));
        assertTrue(csv.stringColumns(List.of(List.of("a"))).isEmpty());
    }

    @Test
    @DisplayName("tag-only cells are not translated")
    void isTranslatableSkipsTagOnlyCells() {
        assertFalse(csv.isTranslatable("<if([Inum1>9999],9\\,999+,<kilo(Inum1,\\,)>)>"));
        assertFalse(csv.isTranslatable("<br><colortype(504)>"));
        assertFalse(csv.isTranslatable("123<br>"));
        assertTrue(csv.isTranslatable("Deal <if(X,Y)> damage."));
        assertTrue(csv.isTranslatable("Say \\<sigh> now"));
    }

    @Test
    @DisplayName("identifier filtering: ACTOR, quest id, caps snake case")
    void isTranslatableSkipsIdentifiers() {
        assertFalse(csv.isTranslatable("ACTOR0"));
        assertFalse(csv.isTranslatable("SEQ_0_ACTOR1"));
        assertFalse(csv.isTranslatable("SubFst010_00001"));
        assertFalse(csv.isTranslatable("ClsHrv001_00003"));
        assertFalse(csv.isTranslatable("UNLOCK_IMAGE_GATHER_BOOK"));
        assertFalse(csv.isTranslatable("HOW_TO_GEAR_SET"));
        assertFalse(csv.isTranslatable("P1S"));
        assertFalse(csv.isTranslatable("Lv70"));
    }

    @Test
    @DisplayName("path filtering: battle/battle_start, normal/idle")
    void isTranslatableSkipsPaths() {
        assertFalse(csv.isTranslatable("battle/battle_start"));
        assertFalse(csv.isTranslatable("normal/idle"));
        assertFalse(csv.isTranslatable("normal/idle/inactive1"));
    }

    @Test
    @DisplayName("real text with lookalike symbols stays: and/or, YES, tags")
    void isTranslatableKeepsRealText() {
        assertTrue(csv.isTranslatable("A Good Adventurer Is Hard to Find"));
        assertTrue(csv.isTranslatable("Deal damage to target."));
        assertTrue(csv.isTranslatable("и/или"));
        assertTrue(csv.isTranslatable("Say \\<sigh> now"));
    }

    @Test
    @DisplayName("noise filtering: blanks, digits, tech keys")
    void isTranslatableFiltersNoise() {
        assertTrue(csv.isTranslatable("Hello world"));
        assertTrue(csv.isTranslatable("  Привет  "));
        assertFalse(csv.isTranslatable(""));
        assertFalse(csv.isTranslatable("   "));
        assertFalse(csv.isTranslatable("12345"));
        assertFalse(csv.isTranslatable("TEXT_SOME_KEY"));
        assertTrue(csv.isTranslatable("Grants <colortype(506)>power."));
    }

    @Test
    @DisplayName("tag extraction <...>")
    void protectedTokensExtractsTags() {
        assertEquals(List.of("<settime(1)>", "<if>"), csv.protectedTokens("A<settime(1)>B<if>C"));
        assertTrue(csv.protectedTokens("plain").isEmpty());
    }

    @Test
    @DisplayName("tokens deduplicated, escaped ones excluded")
    void protectedTokensDistinctWithoutEscaped() {
        assertEquals(List.of("<br>"), csv.protectedTokens("A<br>B<br>\\<sigh>"));
        assertEquals(List.of("<if([gnum72>=94],220,150)>"),
                csv.protectedTokens("Potency <if([gnum72>=94],220,150)>."));
    }

    @Test
    @DisplayName("source outer whitespace is preserved")
    void preserveOuterWhitespaceKeepsPadding() {
        assertEquals("  перевод ", csv.preserveOuterWhitespace("  source ", "перевод"));
        assertEquals("перевод", csv.preserveOuterWhitespace("source", "перевод"));
    }

    @Test
    @DisplayName("shape change (rows/columns/keys) is rejected")
    void validateStructureRejectsShapeChanges() {
        List<List<String>> original = new ArrayList<>();
        original.add(new ArrayList<>(List.of("1", "a")));
        original.add(new ArrayList<>(List.of("2", "b")));
        List<List<String>> same = new ArrayList<>();
        same.add(new ArrayList<>(List.of("1", "a")));
        same.add(new ArrayList<>(List.of("2", "changed")));
        csv.validateStructure(original, same);

        List<List<String>> fewerRows = new ArrayList<>(same.subList(0, 1));
        assertThrows(IllegalArgumentException.class, () -> csv.validateStructure(original, fewerRows));

        List<List<String>> fewerCols = new ArrayList<>();
        fewerCols.add(new ArrayList<>(List.of("1", "a")));
        fewerCols.add(new ArrayList<>(List.of("2")));
        assertThrows(IllegalArgumentException.class, () -> csv.validateStructure(original, fewerCols));

        List<List<String>> changedKey = new ArrayList<>();
        changedKey.add(new ArrayList<>(List.of("1", "a")));
        changedKey.add(new ArrayList<>(List.of("9", "b")));
        assertThrows(IllegalArgumentException.class, () -> csv.validateStructure(original, changedKey));
    }
}
