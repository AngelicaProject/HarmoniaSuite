package com.harmoniasuite.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagSupportTest {

    @Test
    @DisplayName("simple tags are extracted in order")
    void parseSimpleTagsInOrder() {
        var tags = TagSupport.parse("A<br>B<colortype(504)>C");
        assertEquals(List.of("<br>", "<colortype(504)>"), tags.stream().map(TagSupport.Tag::text).toList());
    }

    @Test
    @DisplayName("comparison inside a condition does not split the tag")
    void parseComparisonInsideCondition() {
        var tags = TagSupport.parse("Potency <if([gnum72>=94],220,150)>.");
        assertEquals(List.of("<if([gnum72>=94],220,150)>"), tags.stream().map(TagSupport.Tag::text).toList());
    }

    @Test
    @DisplayName("nested tags parse as a single outer tag")
    void parseNestedTagsAsSingleOuter() {
        String outer = "<if([gnum68==19],<if([gnum72>=84],200,150)>,150)>";
        var tags = TagSupport.parse("Deal " + outer + " damage.");
        assertEquals(List.of(outer), tags.stream().map(TagSupport.Tag::text).toList());
    }

    @Test
    @DisplayName("escaped tag does not count as text")
    void parseIgnoresEscapedTags() {
        assertTrue(TagSupport.parse("Say \\<sigh> now").isEmpty());
        assertEquals(List.of("<br>"), TagSupport.parse("A\\<sigh><br>").stream().map(TagSupport.Tag::text).toList());
    }

    @Test
    @DisplayName("deep parse finds nested tags")
    void parseDeepFindsNested() {
        var texts = TagSupport.parseDeep("A<if(X,<br>)>B").stream().map(TagSupport.Tag::text).toList();
        assertEquals(List.of("<if(X,<br>)>", "<br>"), texts);
    }

    @Test
    @DisplayName("nested tags do not count as unclosed")
    void nestedTagsAreNotStrays() {
        String tr = "Да<br>Нет<if([gnum1==2],<br>Да)>";
        assertTrue(TagSupport.validate(tr).isEmpty());
    }

    @Test
    @DisplayName("one typo gives one positioned error")
    void singleTypoGivesSinglePositionedError() {
        String tr = "Дарует <colortype(506)>Бред<edgecolortype:507>!";
        List<String> errors = TagSupport.validate(tr);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("(позиция 27)"));
        assertTrue(errors.get(0).contains("<edgecolortype:507>"));
    }

    @Test
    @DisplayName("translated if branches produce no warnings")
    void translatedIfBranchesAreQuiet() {
        String src = "A<if([gnum1==2],Yes,No)>B";
        assertTrue(TagSupport.warnings(src, "А<if([gnum1==2],Да,Нет)>Б").isEmpty());
    }

    @Test
    @DisplayName("changed if condition produces a warning")
    void changedIfConditionWarns() {
        List<String> warnings = TagSupport.warnings(
                "A<if([gnum1==2],Yes,No)>", "А<if([gnum1==3],Да,Нет)>");
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(0).contains("<if([gnum1==2])>"));
    }

    @Test
    @DisplayName("compound if conditions normalize without branches")
    void multiConditionIfNormalizesToConditions() {
        assertEquals("<if(gnum68==19)if(gnum72>=54)>",
                TagSupport.normalizedTag("<if(gnum68==19),if(gnum72>=54),<br>X,,)>"));
    }

    @Test
    @DisplayName("editing compound if conditions produces a warning")
    void changedMultiConditionIfWarns() {
        List<String> warnings = TagSupport.warnings(
                "A<if(gnum68==19),if(gnum72>=54),<br>X,,)>",
                "А<if(gnum68==19),if(gnum72>=54),<br>Y,,)>");
        assertTrue(warnings.isEmpty());
        List<String> broken = TagSupport.warnings(
                "A<if(gnum68==19),if(gnum72>=54),<br>X,,)>",
                "А<if(if(gnum68==19), if(if(gnum72>=54),<br>X,,)>");
        assertFalse(broken.isEmpty());
        assertTrue(broken.stream().anyMatch(w -> w.contains("<if(gnum68==19)")));
    }

    @Test
    @DisplayName("unknown code parses as an opaque payload tag")
    void parsePayloadTag() {
        var tags = TagSupport.parse("A<payload: 02>B");
        assertEquals(List.of("<payload: 02>"), tags.stream().map(TagSupport.Tag::text).toList());
        assertTrue(TagSupport.validate("А<payload: 02>Б").isEmpty());
    }

    @Test
    @DisplayName("warning on a changed payload tag")
    void warningsOnChangedPayload() {
        List<String> warnings = TagSupport.warnings("A<payload: 02>", "А<payload: 03>");
        assertEquals(2, warnings.size());
    }

    @Test
    @DisplayName("unclosed brackets do not count as a tag")
    void parseIgnoresUnclosedTags() {
        assertTrue(TagSupport.parse("Broken <colortype(504> text").isEmpty());
        assertTrue(TagSupport.parse("Plain a < b comparison").isEmpty());
    }

    @Test
    @DisplayName("distinct removes duplicates for display")
    void distinctDedupes() {
        assertEquals(List.of("<br>", "<colortype(0)>"),
                TagSupport.distinct("A<br>B<colortype(0)>C<br>D<colortype(0)>"));
    }

    @Test
    @DisplayName("validation accepts matching tags")
    void validateAcceptsMatchingTags() {
        assertTrue(TagSupport.validate("А <br> Б<colortype(1)>").isEmpty());
    }

    @Test
    @DisplayName("validation accepts a blank translation")
    void validateAcceptsBlankTranslation() {
        assertTrue(TagSupport.validate("").isEmpty());
        assertTrue(TagSupport.validate(null).isEmpty());
    }

    @Test
    @DisplayName("validation ignores a different tag set")
    void validateIgnoresTagSetDifference() {
        assertTrue(TagSupport.validate("А<colortype(506)>Б<br>").isEmpty());
        assertTrue(TagSupport.validate("А<BR>Б").isEmpty());
    }

    @Test
    @DisplayName("warnings report missing and extra tags")
    void warningsReportMissingAndExtra() {
        List<String> warnings = TagSupport.warnings(
                "A<colortype(506)>B<edgecolortype(507)>", "А<colortype(506)>Б<br>");
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(0).contains("нет тега <edgecolortype(507)> из оригинала"));
        assertTrue(warnings.get(1).contains("новый тег <br>"));
    }

    @Test
    @DisplayName("warnings stay silent on matching tags")
    void warningsEmptyWhenMatching() {
        assertTrue(TagSupport.warnings("A <br> B<colortype(1)>", "А <br> Б<colortype(1)>").isEmpty());
        assertTrue(TagSupport.warnings("A<br>B<colortype(0)>", "А<colortype(0)>Б<br>").isEmpty());
        assertTrue(TagSupport.warnings("A<br>", "").isEmpty());
    }

    @Test
    @DisplayName("validation accepts reordered tags")
    void validateAcceptsReorderedTags() {
        assertTrue(TagSupport.validate("А<colortype(0)>Б<br>").isEmpty());
    }

    @Test
    @DisplayName("validation reports an unclosed tag in the translation")
    void validateReportsStrayBracket() {
        List<String> issues = TagSupport.validate("Привет <colortype(504");
        assertTrue(issues.stream().anyMatch(m -> m.contains("незакрытый тег")));
    }

    @Test
    @DisplayName("case repair substitutes source tags")
    void repairCasingFixesCase() {
        assertEquals("Дарует <colortype(506)>Бред<edgecolortype(0)>",
                TagSupport.repairCasing("Grants <colortype(506)>Delirium<edgecolortype(0)>",
                        "Дарует <COLORTYPE(506)>Бред<EDGECOLORTYPE(0)>"));
    }

    @Test
    @DisplayName("case repair leaves mismatched sets alone")
    void repairCasingKeepsUnaligned() {
        String translation = "А<br>Б";
        assertEquals(translation, TagSupport.repairCasing("A<colortype(0)>", translation));
        assertEquals(translation, TagSupport.repairCasing("A<br><br>", translation));
    }

    @Test
    @DisplayName("case repair keeps translated if branches")
    void repairCasingKeepsTranslatedBranches() {
        String translation = "А <if(x==1,Да,Нет)> Д";
        assertEquals(translation,
                TagSupport.repairCasing("A <if(x==1,Yes,No)> D", translation));
    }

    @Test
    @DisplayName("parser spans a multi-condition if as a whole")
    void parseSpansMultiConditionIf() {
        String value = "20s <if(gnum68==19),if(gnum72>=54),<br><colortype(504)>X<colortype(0)>,,)>";
        List<TagSupport.Tag> tags = TagSupport.parseTopLevel(value);
        assertEquals(1, tags.size());
        assertEquals("if", tags.get(0).name());
        assertEquals(4, tags.get(0).start());
        assertEquals(value.length(), tags.get(0).end());
    }

    @Test
    @DisplayName("validation does not flag a game if as unclosed")
    void validateAcceptsGameIf() {
        assertTrue(TagSupport.validate(
                "20s <if(gnum68==19),if(gnum72>=54),<br><colortype(504)>X<colortype(0)>,,)>!")
                .isEmpty());
    }
}
