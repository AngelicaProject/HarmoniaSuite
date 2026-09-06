package com.harmoniasuite.util;

import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class CsvSupport {

    public static final int DATA_START_ROW = 4;
    private static final Pattern LETTER_PATTERN = Pattern.compile("[\\p{L}]");
    private static final Pattern TECHNICAL_KEY_PATTERN = Pattern.compile("^TEXT_[A-Z0-9_]+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CAPS_ID_PATTERN = Pattern.compile("^(?=.*[\\d_])[A-Z0-9_]+$");
    private static final Pattern CAPS_ID_SPACED_PATTERN = Pattern.compile("^(?=.*_)[A-Z0-9_ ]+$");
    private static final Pattern ALNUM_ID_PATTERN = Pattern.compile("^(?=.*[A-Z])(?=.*\\d)[A-Za-z0-9]+$");
    private static final Pattern PATH_PATTERN = Pattern.compile("^(?=.*/)[\\w./-]+$");

    public List<Path> findCsvFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv"))
                    .sorted((a, b) -> a.toString().compareToIgnoreCase(b.toString()))
                    .toList();
        }
    }

    public List<List<String>> readRows(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            // strip BOM if present
            reader.mark(1);
            int first = reader.read();
            if (first != '\uFEFF') {
                reader.reset();
            }
            return parseCsv(reader);
        }
    }

    public void writeRowsAtomic(Path path, List<List<String>> rows) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), "." + path.getFileName() + ".", ".tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            for (List<String> row : rows) {
                writer.write(formatCsvRow(row));
                writer.write('\n');
            }
        } catch (Exception e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
    }

    public List<Integer> stringColumns(List<List<String>> rows) {
        if (rows.size() <= 3) {
            return List.of();
        }
        List<String> types = rows.get(3);
        List<Integer> columns = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            if ("string".equalsIgnoreCase(types.get(i).trim())) {
                columns.add(i);
            }
        }
        return columns;
    }

    public boolean isTranslatable(String value) {
        String trimmed = value.strip();
        if (trimmed.isEmpty() || TECHNICAL_KEY_PATTERN.matcher(trimmed).matches()) {
            return false;
        }
        String text = stripTags(trimmed).strip();
        if (text.isEmpty() || !LETTER_PATTERN.matcher(text).find()) {
            return false;
        }
        if (text.chars().noneMatch(Character::isWhitespace) && text.contains("_")) {
            return false;
        }
        return !CAPS_ID_PATTERN.matcher(text).matches()
                && !CAPS_ID_SPACED_PATTERN.matcher(text).matches()
                && !ALNUM_ID_PATTERN.matcher(text).matches()
                && !PATH_PATTERN.matcher(text).matches();
    }

    private static String stripTags(String value) {
        List<TagSupport.Tag> tags = TagSupport.parseTopLevel(value);
        if (tags.isEmpty()) {
            return value;
        }
        StringBuilder out = new StringBuilder();
        int pos = 0;
        for (TagSupport.Tag tag : tags) {
            out.append(value, pos, tag.start());
            pos = tag.end();
        }
        return out.append(value, pos, value.length()).toString();
    }

    public List<String> protectedTokens(String source) {
        return TagSupport.distinct(source);
    }

    public String preserveOuterWhitespace(String original, String translation) {
        int leading = original.length() - original.stripLeading().length();
        int trailing = original.length() - original.stripTrailing().length();
        String lead = original.substring(0, leading);
        String trail = trailing > 0 ? original.substring(original.length() - trailing) : "";
        return lead + translation + trail;
    }

    public void validateStructure(List<List<String>> original, List<List<String>> changed) {
        if (original.size() != changed.size()) {
            throw new HarmoniaSuiteBadRequestException("row count changed");
        }
        for (int i = 0; i < original.size(); i++) {
            List<String> before = original.get(i);
            List<String> after = changed.get(i);
            if (before.size() != after.size()) {
                throw new HarmoniaSuiteBadRequestException("column count changed on row " + (i + 1));
            }
            if (!before.isEmpty() && !after.isEmpty() && !before.getFirst().equals(after.getFirst())) {
                throw new HarmoniaSuiteBadRequestException("row key changed on row " + (i + 1));
            }
        }
    }

    private List<List<String>> parseCsv(Reader reader) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int c;
        while ((c = reader.read()) != -1) {
            char ch = (char) c;
            if (inQuotes) {
                if (ch == '"') {
                    reader.mark(1);
                    int next = reader.read();
                    if (next == '"') {
                        field.append('"');
                    } else {
                        inQuotes = false;
                        if (next != -1) {
                            reader.reset();
                        }
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else if (ch != '\r') {
                field.append(ch);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    private String formatCsvRow(List<String> row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escapeField(row.get(i)));
        }
        return sb.toString();
    }

    private String escapeField(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
