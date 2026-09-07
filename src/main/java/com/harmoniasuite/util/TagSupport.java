package com.harmoniasuite.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FFXIV Lumina text macros, as emitted by ReadOnlySeString.ToMacroString():
 * {@code <br>}, {@code <colortype(504)>}, {@code <if(cond,a,b)>} (nestable),
 * {@code <payload: ...>} for unknown macro codes, and so on.
 * Tag names are MacroCode.GetEncodeName() values (see Lumina
 * src/Lumina/Text/Payloads/MacroCode.cs); matching is case-sensitive, like
 * Lumina's MacroStringParser. A backslash escapes the next char, so
 * {@code \<sigh>} is literal text, not a tag.
 */
public final class TagSupport {

    private TagSupport() {
    }

    public record Tag(String text, String name, int start, int end) {
    }

    /**
     * Hidden speaker name {@code (-Name-)}, parsed by the game client as the
     * dialogue nameplate (unknown speakers show {@code (-???-)}). The wrapper
     * is engine syntax, the inner text is translated.
     */
    public record Anon(String text, String inner, int start, int end) {
    }

    /** Top-level anonymizer spans, in document order. */
    public static List<Anon> parseAnon(String value) {
        List<Anon> out = new ArrayList<>();
        if (value == null || value.isEmpty()) {
            return out;
        }
        int n = value.length();
        int i = 0;
        while (i + 1 < n) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (c == '(' && value.charAt(i + 1) == '-') {
                int end = anonEnd(value, i);
                if (end > i) {
                    out.add(new Anon(value.substring(i, end), value.substring(i + 2, end - 2), i, end));
                    i = end;
                    continue;
                }
            }
            i++;
        }
        return out;
    }

    /** End (exclusive) of the {@code (-...-)} token at {@code start}, or -1. */
    private static int anonEnd(String s, int start) {
        int n = s.length();
        int i = start + 2;
        while (i + 1 < n) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r') {
                return -1;
            }
            if (c == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (c == '-' && s.charAt(i + 1) == ')') {
                return anonTextPresent(s, start + 2, i) ? i + 2 : -1;
            }
            i++;
        }
        return -1;
    }

    private static boolean anonTextPresent(String s, int from, int to) {
        return s.substring(from, to).codePoints()
                .anyMatch(cp -> cp == '?' || cp == '？' || Character.isLetterOrDigit(cp));
    }

    private static boolean anonOpenNext(char c) {
        return c == '?' || c == '？' || c == '<' || c == '"' || c == '\''
                || c == '“' || c == '”' || Character.isLetterOrDigit(c);
    }

    /** Top-level tags only (for highlighting and span replacement). */
    public static List<Tag> parseTopLevel(String value) {
        return parse(value);
    }

    /** All tags including nested ones, in document order (for validation). */
    public static List<Tag> parseDeep(String value) {
        List<Tag> out = new ArrayList<>();
        walkDeep(value == null ? "" : value, 0, out);
        return out;
    }

    private static void walkDeep(String value, int base, List<Tag> out) {
        for (Tag tag : parse(value)) {
            out.add(new Tag(tag.text(), tag.name(), tag.start() + base, tag.end() + base));
            if (tag.end() - tag.start() > 3) {
                walkDeep(value.substring(tag.start() + 1, tag.end()), base + tag.start() + 1, out);
            }
        }
    }

    public static List<Tag> parse(String value) {
        List<Tag> tags = new ArrayList<>();
        if (value == null || value.isEmpty()) {
            return tags;
        }
        int n = value.length();
        int i = 0;
        while (i < n) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (c == '<') {
                int end = parsePayloadEnd(value, i);
                if (end < 0) {
                    end = parseTagEnd(value, i);
                }
                if (end > i) {
                    tags.add(new Tag(value.substring(i, end), tagName(value, i), i, end));
                    i = end;
                    continue;
                }
            }
            i++;
        }
        return tags;
    }

    /** Tags in order of appearance, duplicates kept, nested included. */
    public static List<String> sequence(String value) {
        return parseDeep(value).stream().map(Tag::text).toList();
    }

    /** Distinct tags in order of first appearance, nested included. */
    public static List<String> distinct(String value) {
        Set<String> seen = new LinkedHashSet<>();
        for (Tag tag : parseDeep(value)) {
            seen.add(tag.text());
        }
        return new ArrayList<>(seen);
    }

    private static final Set<String> LOGIC_TAGS = Set.of(
            "if", "switch", "ifpcgender", "ifpcname", "ifself", "switchplatform");

    /**
     * Normalizes a tag for set comparison: logic macros carry translated
     * display text in their branches, so only name + condition (first
     * argument) participate in the comparison.
     */
    static String normalizedTag(String text) {
        int paren = text.indexOf('(');
        String name = paren < 0 ? tagNameOf(text) : text.substring(1, paren);
        if (!LOGIC_TAGS.contains(name)) {
            return text;
        }
        if (paren < 0 || !text.endsWith(">")) {
            return text;
        }
        int depth = 0;
        int brackets = 0;
        for (int i = paren; i < text.length() - 1; i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() - 1) {
                i++;
                continue;
            }
            if (c == '<') {
                int end = parsePayloadEnd(text, i);
                if (end < 0) {
                    end = parseTagEnd(text, i);
                }
                if (end > i) {
                    i = end - 1;
                    continue;
                }
            }
            if (c == '[') {
                brackets++;
            } else if (c == ']') {
                if (brackets > 0) {
                    brackets--;
                }
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 1 && brackets == 0) {
                return "<" + name + text.substring(paren, i) + ")>";
            }
        }
        return logicConditionGroups(text, name, paren);
    }

    /**
     * Fallback when no top-level comma splits head from branches (composite
     * conditions like {@code <if(g1),if(g2),branches...>}): leading balanced
     * groups, so condition edits compare readably instead of whole-blob.
     */
    private static String logicConditionGroups(String text, String name, int paren) {
        StringBuilder groups = new StringBuilder();
        int i = paren;
        int n = text.length() - 1;
        while (i < n) {
            char c = text.charAt(i);
            if (c == ',' || c == ' ' || c == '\t') {
                i++;
                continue;
            }
            if (c == '<' || c == '>' || c == ')') {
                break;
            }
            int end = scanConditionGroup(text, i, n);
            if (end < 0) {
                break;
            }
            groups.append(text, i, end);
            i = end;
        }
        return groups.length() == 0 ? text : "<" + name + groups + ">";
    }

    /** End (exclusive) of a {@code name(...)} / {@code (...)} group, or -1. */
    private static int scanConditionGroup(String text, int i, int n) {
        int j = i;
        while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) {
            j++;
        }
        if (j >= n || text.charAt(j) != '(') {
            return -1;
        }
        int depth = 0;
        int k = j;
        while (k < n) {
            char c = text.charAt(k);
            if (c == '\\' && k + 1 < n) {
                k += 2;
                continue;
            }
            if (c == '<') {
                return -1;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return k + 1;
                }
            }
            k++;
        }
        return -1;
    }

    private static String tagNameOf(String text) {
        int i = text.startsWith("<payload:") ? -1 : 1;
        if (i < 0) {
            return "payload";
        }
        int start = i;
        while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) {
            i++;
        }
        return text.substring(start, i);
    }

    static String shortTag(String text) {
        return text.length() > 80 ? text.substring(0, 80) + "..." : text;
    }

    /**
     * Hard errors that break the game client and block saving:
     * malformed or unclosed tags in the translation.
     * Empty translation means "untranslated" and is always accepted.
     */
    public static List<String> validate(String translation) {
        if (translation == null || translation.isBlank()) {
            return new ArrayList<>();
        }
        List<String> errors = new ArrayList<>();
        for (Stray stray : strayTags(translation, parseDeep(translation))) {
            errors.add("похоже на незакрытый тег (позиция " + stray.pos() + "): " + shortTag(stray.snippet()));
        }
        Set<Integer> anonStarts = new HashSet<>();
        for (Anon anon : parseAnon(translation)) {
            anonStarts.add(anon.start());
        }
        int n = translation.length();
        for (int i = 0; i + 1 < n; i++) {
            if (translation.charAt(i) == '(' && translation.charAt(i + 1) == '-'
                    && !anonStarts.contains(i) && !escaped(translation, i)
                    && i + 2 < n && anonOpenNext(translation.charAt(i + 2))) {
                int end = Math.min(n, i + 24);
                errors.add("похоже на незакрытую конструкцию (-...-) (позиция " + i + "): "
                        + shortTag(translation.substring(i, end) + (end < n ? "..." : "")));
            }
        }
        return errors;
    }

    /**
     * Soft warnings that never block saving: original tags missing from the
     * translation, or new tags added. Translators may add tags the game
     * understands (e.g. gender branches for gendered languages), and tag
     * order may follow target grammar, so only the difference is reported.
     */
    public static List<String> warnings(String source, String translation) {
        List<String> warnings = new ArrayList<>();
        if (translation == null || translation.isBlank()) {
            return warnings;
        }
        List<String> want = sequence(source == null ? "" : source).stream().map(TagSupport::normalizedTag).toList();
        List<String> got = sequence(translation).stream().map(TagSupport::normalizedTag).toList();
        if (want.equals(got)) {
            return anonWarnings(source, translation);
        }
        Map<String, Integer> wantCounts = counts(want);
        Map<String, Integer> gotCounts = counts(got);
        for (Map.Entry<String, Integer> e : wantCounts.entrySet()) {
            int missing = e.getValue() - gotCounts.getOrDefault(e.getKey(), 0);
            if (missing > 0) {
                warnings.add("нет тега " + shortTag(e.getKey()) + " из оригинала" + times(missing));
            }
        }
        for (Map.Entry<String, Integer> e : gotCounts.entrySet()) {
            int extra = e.getValue() - wantCounts.getOrDefault(e.getKey(), 0);
            if (extra > 0) {
                warnings.add("новый тег " + shortTag(e.getKey()) + times(extra));
            }
        }
        warnings.addAll(anonWarnings(source, translation));
        return warnings;
    }

    private static List<String> anonWarnings(String source, String translation) {
        List<String> warnings = new ArrayList<>();
        if (translation == null || translation.isBlank()) {
            return warnings;
        }
        List<Anon> wantAnon = parseAnon(source == null ? "" : source);
        List<Anon> gotAnon = parseAnon(translation);
        for (int i = gotAnon.size(); i < wantAnon.size(); i++) {
            warnings.add("нет конструкции " + shortTag(wantAnon.get(i).text()) + " из оригинала");
        }
        for (int i = wantAnon.size(); i < gotAnon.size(); i++) {
            warnings.add("новая конструкция " + shortTag(gotAnon.get(i).text()));
        }
        return warnings;
    }

    /**
     * Repairs tag casing in a machine translation: when tag names line up
     * positionally (case-insensitive), takes exact tag texts from the source —
     * but only when the texts differ by case alone, so translated text inside
     * logic branches ({@code <if(c,Yes,No)>}) is never reverted to English.
     * Returns the input unchanged when tags cannot be aligned.
     */
    public static String repairCasing(String source, String translation) {
        if (source == null || translation == null || translation.isBlank()) {
            return translation;
        }
        List<Tag> want = parse(source);
        List<Tag> got = parse(translation);
        if (want.size() != got.size()) {
            return translation;
        }
        for (int i = 0; i < want.size(); i++) {
            if (!want.get(i).name().equalsIgnoreCase(got.get(i).name())) {
                return translation;
            }
        }
        StringBuilder out = new StringBuilder(translation);
        for (int i = got.size() - 1; i >= 0; i--) {
            Tag w = want.get(i);
            Tag g = got.get(i);
            if (!w.text().equals(g.text()) && w.text().equalsIgnoreCase(g.text())) {
                out.replace(g.start(), g.end(), w.text());
            }
        }
        return out.toString();
    }

    private static String tagName(String s, int start) {
        int i = start + 1;
        int nameStart = i;
        while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
            i++;
        }
        return s.substring(nameStart, i);
    }

    /** Lumina emits {@code <payload: XX>} for unknown macro codes; opaque tag. */
    private static int parsePayloadEnd(String s, int start) {
        if (!s.startsWith("<payload:", start)) {
            return -1;
        }
        int close = s.indexOf('>', start + 9);
        return close < 0 ? -1 : close + 1;
    }

    /** Index after the closing {@code >}, or -1 when no valid tag starts here. */
    private static int parseTagEnd(String s, int start) {
        int n = s.length();
        int i = start + 1;
        int nameStart = i;
        while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
            i++;
        }
        if (i == nameStart || !Character.isLetter(s.charAt(nameStart))) {
            return -1;
        }
        if (i < n && s.charAt(i) == '(') {
            int after;
            if (LOGIC_TAGS.contains(s.substring(nameStart, i))) {
                after = parseLogicArgs(s, i);
            } else {
                after = parseBalanced(s, i);
            }
            if (after < 0) {
                return -1;
            }
            i = after;
        }
        return i < n && s.charAt(i) == '>' ? i + 1 : -1;
    }

    /**
     * s.charAt(start) is {@code (} of a logic macro ({@code if}/{@code switch}/...):
     * arguments run to the {@code >} closing the tag and may contain commas,
     * nested tags, comparisons and bare groups (real game data:
     * {@code <if(gnum68==19),if(gnum72>=54),<br>...,,)>}). The tag ends at a
     * {@code >} that closes the argument list — i.e. depth back at/below zero
     * with {@code )} right before it ({@code >=} inside conditions is skipped).
     * Index of the closing {@code >} (like {@link #parseBalanced}), or -1.
     */
    private static int parseLogicArgs(String s, int start) {
        int n = s.length();
        int depth = 0;
        boolean seenParen = false;
        int i = start;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (c == '<') {
                int end = parsePayloadEnd(s, i);
                if (end < 0) {
                    end = parseTagEnd(s, i);
                }
                if (end > i) {
                    i = end;
                    continue;
                }
                i++;
                continue;
            }
            if (c == '(') {
                depth++;
                seenParen = true;
            } else if (c == ')') {
                depth--;
            } else if (c == '>' && depth <= 0
                    && (s.charAt(i - 1) == ')' || !seenParen)) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /** s.charAt(start) is {@code (}; index after the matching {@code )}, or -1. */
    private static int parseBalanced(String s, int start) {
        int n = s.length();
        int depth = 0;
        int i = start;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (c == '<') {
                int end = parseTagEnd(s, i);
                if (end > i) {
                    i = end;
                    continue;
                }
                i++;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
            i++;
        }
        return -1;
    }

    private record Stray(int pos, String snippet) {
    }

    private static List<Stray> strayTags(String value, List<Tag> parsed) {
        Set<Integer> starts = new java.util.HashSet<>();
        for (Tag tag : parsed) {
            starts.add(tag.start());
        }
        List<Stray> strays = new ArrayList<>();
        int n = value.length();
        for (int i = 0; i + 1 < n; i++) {
            if (value.charAt(i) == '<' && Character.isLetter(value.charAt(i + 1))
                    && !starts.contains(i) && !escaped(value, i)) {
                int end = Math.min(n, i + 24);
                strays.add(new Stray(i, value.substring(i, end) + (end < n ? "..." : "")));
            }
        }
        return strays;
    }

    private static boolean escaped(String s, int pos) {
        int backslashes = 0;
        for (int i = pos - 1; i >= 0 && s.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    private static Map<String, Integer> counts(List<String> tags) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String tag : tags) {
            counts.merge(tag, 1, Integer::sum);
        }
        return counts;
    }

    private static String times(int count) {
        return count > 1 ? " (x" + count + ")" : "";
    }
}
