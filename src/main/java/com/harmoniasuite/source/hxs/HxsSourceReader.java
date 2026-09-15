package com.harmoniasuite.source.hxs;

import com.harmoniasuite.source.atlas.AtlasInspection;
import org.sqlite.SQLiteConfig;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Streaming, read-only reader for an HXS SQLite artifact already verified by Atlas. */
public class HxsSourceReader {

    private static final int FETCH_SIZE = 2_000;

    /**
     * Reads metadata first and then emits one sheet's records at a time. The sink may return
     * false from {@link HxsSourceSink#begin(HxsMetadata)} to stop after the metadata check.
     */
    public void read(Path hxsPath, AtlasInspection trustedInspection, HxsSourceSink sink) {
        Objects.requireNonNull(trustedInspection, "trustedInspection");
        Objects.requireNonNull(sink, "sink");
        Path path = requireReadableFile(hxsPath);

        try (Connection connection = openReadOnlyConnection(path)) {
            HxsMetadata metadata = readMetadata(connection);
            metadata.requireExactMatch(trustedInspection);
            if (!sink.begin(metadata)) {
                return;
            }

            HxsLayout layout = HxsLayout.discover(connection);
            readSheets(connection, layout, sink);
            sink.end();
        } catch (HxsReadException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw new HxsReadException(HxsReadException.Reason.READ_FAILURE,
                    "HXS could not be read", exception);
        }
    }

    /** Package-private seam used by read-only tests; callers must close the returned connection. */
    Connection openReadOnlyConnection(Path hxsPath) throws SQLException {
        Path path = requireReadableFile(hxsPath);
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setExplicitReadOnly(true);
        Connection connection;
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + path, config.toProperties());
            connection.setReadOnly(true);
            return connection;
        } catch (SQLException exception) {
            throw new HxsReadException(HxsReadException.Reason.OPEN_FAILURE,
                    "HXS could not be opened in read-only mode", exception);
        }
    }

    private static Path requireReadableFile(Path path) {
        if (path == null) {
            throw new HxsReadException(HxsReadException.Reason.INPUT, "HXS path must not be null");
        }
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isReadable(path)) {
                throw new HxsReadException(HxsReadException.Reason.INPUT,
                        "HXS path must be a readable regular file");
            }
            return path.toAbsolutePath().normalize();
        } catch (SecurityException exception) {
            throw new HxsReadException(HxsReadException.Reason.INPUT,
                    "HXS path cannot be inspected", exception);
        }
    }

    private static HxsMetadata readMetadata(Connection connection) throws SQLException {
        TableLayout metadataTable = TableLayout.discover(connection, "hxs_meta");
        Map<String, Object> values = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT * FROM " + quote(metadataTable.tableName()))) {
            ResultSetMetaData resultMetadata = resultSet.getMetaData();
            Map<String, Integer> columns = resultMetadataColumns(resultMetadata);
            boolean keyValue = findColumn(columns, "key", "meta_key", "name") != null
                    && findColumn(columns, "value", "meta_value") != null;
            if (keyValue) {
                int keyIndex = findColumn(columns, "key", "meta_key", "name");
                int valueIndex = findColumn(columns, "value", "meta_value");
                while (resultSet.next()) {
                    Object key = resultSet.getObject(keyIndex);
                    if (key == null || key.toString().isBlank()) {
                        throw schemaError("hxs_meta contains a blank metadata key");
                    }
                    values.put(normalize(key.toString()), resultSet.getObject(valueIndex));
                }
            } else {
                if (!resultSet.next()) {
                    throw schemaError("hxs_meta must contain one metadata record");
                }
                for (int index = 1; index <= resultMetadata.getColumnCount(); index++) {
                    values.put(normalize(resultMetadata.getColumnLabel(index)),
                            resultSet.getObject(index));
                }
                if (resultSet.next()) {
                    throw schemaError("hxs_meta must contain one metadata record");
                }
            }
        }

        return new HxsMetadata(
                requiredInt(values, "hxs version", "hxs_version", "hxsVersion", "format_version", "formatVersion"),
                requiredText(values, "game version", "game_version", "gameVersion"),
                requiredText(values, "language", "language"),
                requiredText(values, "scope", "scope"),
                requiredText(values, "snapshot ID", "snapshot_id", "snapshotId"),
                requiredText(values, "content ID", "content_id", "contentId"),
                requiredText(values, "extractor version", "extractor_version", "extractorVersion"),
                requiredText(values, "Lumina version", "lumina_version", "luminaVersion"),
                requiredNonNegativeLong(values, "sheet count", "sheet_count", "sheetCount"),
                requiredNonNegativeLong(values, "row count", "row_count", "rowCount"),
                requiredNonNegativeLong(values, "String cell count", "string_cell_count", "stringCellCount"));
    }

    private static void readSheets(Connection connection, HxsLayout layout, HxsSourceSink sink)
            throws SQLException {
        String select = layout.sheets().select(
                layout.sheets().column("name", "sheet_name", "sheetName"),
                layout.sheets().column("variant"),
                layout.sheets().column("effective_language", "effectiveLanguage", "language"),
                layout.sheets().column("column_count", "columnCount"),
                layout.sheets().column("row_count", "rowCount"),
                layout.sheets().column("schema_hash", "schemaHash"),
                layout.sheets().column("technical_hash", "technicalHash"),
                layout.sheets().column("string_hash", "stringHash"),
                layout.sheets().column("content_hash", "contentHash"));
        String sql = "SELECT " + select + " FROM " + quote(layout.sheets().tableName())
                + " ORDER BY " + quote(layout.sheets().column("name", "sheet_name", "sheetName"));
        try (Statement statement = forwardOnlyStatement(connection, FETCH_SIZE);
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                HxsSheet sheet = new HxsSheet(
                        requiredText(resultSet, 1, "sheet name"),
                        requiredInt(resultSet, 2, "sheet variant"),
                        requiredText(resultSet, 3, "effective language"),
                        requiredNonNegativeLong(resultSet, 4, "sheet column count"),
                        requiredNonNegativeLong(resultSet, 5, "sheet row count"),
                        requiredHash(resultSet, 6, "schema hash"),
                        requiredHash(resultSet, 7, "technical hash"),
                        requiredHash(resultSet, 8, "string hash"),
                        requiredHash(resultSet, 9, "content hash"));
                sink.beginSheet(sheet);
                readColumns(connection, layout.columns(), sheet.name(), sink);
                readRows(connection, layout.rows(), sheet.name(), sink);
                readStringCells(connection, layout.stringCells(), sheet.name(), sink);
                sink.endSheet();
            }
        }
    }

    private static void readColumns(Connection connection, TableLayout layout, String sheetName,
                                    HxsSourceSink sink) throws SQLException {
        String sheetColumn = layout.column("sheet_name", "sheetName", "sheet");
        String columnIndex = layout.column("column_index", "columnIndex");
        String sql = "SELECT " + layout.select(columnIndex, layout.column("offset"), layout.column("type"))
                + " FROM " + quote(layout.tableName()) + " WHERE " + quote(sheetColumn)
                + " = ? ORDER BY " + quote(columnIndex);
        try (PreparedStatement statement = prepared(connection, sql, FETCH_SIZE)) {
            statement.setString(1, sheetName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    sink.column(new HxsColumn(
                            requiredInt(resultSet, 1, "column index"),
                            requiredLong(resultSet, 2, "column offset"),
                            requiredText(resultSet, 3, "column type")));
                }
            }
        }
    }

    private static void readRows(Connection connection, TableLayout layout, String sheetName,
                                 HxsSourceSink sink) throws SQLException {
        String sheetColumn = layout.column("sheet_name", "sheetName", "sheet");
        String rowId = layout.column("row_id", "rowId");
        String subrowId = layout.column("subrow_id", "subrowId");
        String sql = "SELECT " + layout.select(rowId, subrowId,
                layout.column("row_hash", "rowHash"),
                layout.column("technical_hash", "technicalHash"),
                layout.column("string_hash", "stringHash"))
                + " FROM " + quote(layout.tableName()) + " WHERE " + quote(sheetColumn)
                + " = ? ORDER BY " + quote(rowId) + ", " + quote(subrowId);
        try (PreparedStatement statement = prepared(connection, sql, FETCH_SIZE)) {
            statement.setString(1, sheetName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    sink.row(new HxsRow(
                            requiredLong(resultSet, 1, "row ID"),
                            requiredInt(resultSet, 2, "subrow ID"),
                            requiredHash(resultSet, 3, "row hash"),
                            requiredHash(resultSet, 4, "technical hash"),
                            requiredHash(resultSet, 5, "string hash")));
                }
            }
        }
    }

    private static void readStringCells(Connection connection, TableLayout layout, String sheetName,
                                         HxsSourceSink sink) throws SQLException {
        String sheetColumn = layout.column("sheet_name", "sheetName", "sheet");
        String rowId = layout.column("row_id", "rowId");
        String subrowId = layout.column("subrow_id", "subrowId");
        String columnIndex = layout.column("column_index", "columnIndex");
        String sql = "SELECT " + layout.select(rowId, subrowId, columnIndex,
                layout.column("macro_text", "macroText"),
                layout.column("macro_hash", "macroHash"),
                layout.column("raw_hash", "rawHash"))
                + " FROM " + quote(layout.tableName()) + " WHERE " + quote(sheetColumn)
                + " = ? ORDER BY " + quote(rowId) + ", " + quote(subrowId) + ", " + quote(columnIndex);
        try (PreparedStatement statement = prepared(connection, sql, FETCH_SIZE)) {
            statement.setString(1, sheetName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    sink.stringCell(new HxsStringCell(
                            requiredLong(resultSet, 1, "String cell row ID"),
                            requiredInt(resultSet, 2, "String cell subrow ID"),
                            requiredInt(resultSet, 3, "String cell column index"),
                            requiredText(resultSet, 4, "macro text"),
                            requiredHash(resultSet, 5, "macro hash"),
                            nullableHash(resultSet, 6, "raw hash")));
                }
            }
        }
    }

    private static Statement forwardOnlyStatement(Connection connection, int fetchSize) throws SQLException {
        Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY);
        statement.setFetchSize(fetchSize);
        return statement;
    }

    private static PreparedStatement prepared(Connection connection, String sql, int fetchSize)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY);
        statement.setFetchSize(fetchSize);
        return statement;
    }

    private static byte[] nullableHash(ResultSet resultSet, int index, String field) throws SQLException {
        Object value = resultSet.getObject(index);
        if (value == null) {
            return null;
        }
        return hashBytes(value, field);
    }

    private static byte[] requiredHash(ResultSet resultSet, int index, String field) throws SQLException {
        Object value = resultSet.getObject(index);
        if (value == null) {
            throw schemaError(field + " is null");
        }
        return hashBytes(value, field);
    }

    private static byte[] hashBytes(Object value, String field) {
        if (!(value instanceof byte[] bytes) || bytes.length != 32) {
            throw schemaError(field + " must be exactly 32 raw bytes");
        }
        return bytes;
    }

    private static String requiredText(ResultSet resultSet, int index, String field) throws SQLException {
        Object value = resultSet.getObject(index);
        if (value == null || value.toString().isBlank()) {
            throw schemaError(field + " is blank");
        }
        return value.toString();
    }

    private static int requiredInt(ResultSet resultSet, int index, String field) throws SQLException {
        long value = requiredLong(resultSet, index, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw schemaError(field + " is outside the integer range");
        }
        return (int) value;
    }

    private static long requiredLong(ResultSet resultSet, int index, String field) throws SQLException {
        Object value = resultSet.getObject(index);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
                // Convert to the same fail-closed schema error below.
            }
        }
        throw schemaError(field + " is not an integer");
    }

    private static int requiredInt(Map<String, Object> values, String field, String... aliases) {
        long value = requiredLong(values, field, aliases);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw schemaError(field + " is outside the integer range");
        }
        return (int) value;
    }

    private static long requiredNonNegativeLong(ResultSet resultSet, int index, String field)
            throws SQLException {
        long value = requiredLong(resultSet, index, field);
        if (value < 0) {
            throw schemaError(field + " must be non-negative");
        }
        return value;
    }

    private static long requiredNonNegativeLong(Map<String, Object> values, String field,
                                                String... aliases) {
        long value = requiredLong(values, field, aliases);
        if (value < 0) {
            throw schemaError(field + " must be non-negative");
        }
        return value;
    }

    private static String requiredText(Map<String, Object> values, String field, String... aliases) {
        Object value = findValue(values, aliases);
        if (value == null || value.toString().isBlank()) {
            throw schemaError(field + " is missing or blank");
        }
        return value.toString();
    }

    private static long requiredLong(Map<String, Object> values, String field, String... aliases) {
        Object value = findValue(values, aliases);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
                // Convert to the same fail-closed schema error below.
            }
        }
        throw schemaError(field + " is missing or not an integer");
    }

    private static Object findValue(Map<String, Object> values, String... aliases) {
        for (String alias : aliases) {
            String normalized = normalize(alias);
            if (values.containsKey(normalized)) {
                return values.get(normalized);
            }
        }
        return null;
    }

    private static Map<String, Integer> resultMetadataColumns(ResultSetMetaData metadata) throws SQLException {
        Map<String, Integer> columns = new HashMap<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            columns.putIfAbsent(normalize(metadata.getColumnLabel(index)), index);
            columns.putIfAbsent(normalize(metadata.getColumnName(index)), index);
        }
        return columns;
    }

    private static Integer findColumn(Map<String, Integer> columns, String... aliases) {
        for (String alias : aliases) {
            Integer index = columns.get(normalize(alias));
            if (index != null) {
                return index;
            }
        }
        return null;
    }

    private static String normalize(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = Character.toLowerCase(value.charAt(index));
            if (character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9') {
                normalized.append(character);
            }
        }
        return normalized.toString();
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static HxsReadException schemaError(String message) {
        return new HxsReadException(HxsReadException.Reason.SCHEMA, message);
    }

    private record HxsLayout(TableLayout sheets, TableLayout columns, TableLayout rows,
                             TableLayout stringCells) {

        private static HxsLayout discover(Connection connection) throws SQLException {
            return new HxsLayout(
                    TableLayout.discover(connection, "hxs_sheets", "sheets", "sheet_records", "sheet"),
                    TableLayout.discover(connection, "hxs_columns", "columns", "sheet_columns", "column"),
                    TableLayout.discover(connection, "hxs_rows", "rows", "sheet_rows", "row"),
                    TableLayout.discover(connection, "hxs_string_cells", "string_cells", "string_cell",
                            "cells"));
        }
    }

    private record TableLayout(String tableName, Map<String, String> columns) {

        private static TableLayout discover(Connection connection, String... candidates)
                throws SQLException {
            Set<String> tables = new HashSet<>();
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type IN ('table', 'view')")) {
                while (resultSet.next()) {
                    tables.add(resultSet.getString(1));
                }
            }
            String table = null;
            for (String candidate : candidates) {
                for (String available : tables) {
                    if (candidate.equalsIgnoreCase(available)) {
                        table = available;
                        break;
                    }
                }
                if (table != null) {
                    break;
                }
            }
            if (table == null) {
                throw schemaError("required HXS table is missing: " + candidates[0]);
            }

            Map<String, String> columns = new LinkedHashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT * FROM " + quote(table) + " LIMIT 0")) {
                ResultSetMetaData metadata = resultSet.getMetaData();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    columns.putIfAbsent(normalize(metadata.getColumnLabel(index)),
                            metadata.getColumnLabel(index));
                    columns.putIfAbsent(normalize(metadata.getColumnName(index)),
                            metadata.getColumnName(index));
                }
            }
            return new TableLayout(table, columns);
        }

        private String column(String... aliases) {
            for (String alias : aliases) {
                String column = columns.get(normalize(alias));
                if (column != null) {
                    return column;
                }
            }
            throw schemaError("required HXS column is missing from " + tableName + ": " + aliases[0]);
        }

        private String select(String... selectedColumns) {
            List<String> quoted = new ArrayList<>(selectedColumns.length);
            for (String selectedColumn : selectedColumns) {
                quoted.add(quote(selectedColumn));
            }
            return String.join(", ", quoted);
        }
    }
}
