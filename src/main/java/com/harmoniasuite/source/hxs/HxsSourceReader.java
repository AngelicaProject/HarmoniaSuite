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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

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

            readSheets(connection, sink);
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
        try {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path,
                    config.toProperties());
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
        try (Statement statement = forwardOnlyStatement(connection, FETCH_SIZE);
             ResultSet resultSet = statement.executeQuery("""
                     SELECT id, format_version, game_version, language, scope,
                            snapshot_id, content_id, extractor_version, lumina_version,
                            sheet_count, row_count, string_cell_count
                     FROM hxs_meta
                     ORDER BY id
                     """)) {
            if (!resultSet.next()) {
                throw schemaError("hxs_meta must contain one record with id = 1");
            }
            if (requiredLong(resultSet, 1, "hxs_meta.id") != 1) {
                throw schemaError("hxs_meta.id must be 1");
            }
            HxsMetadata metadata = new HxsMetadata(
                    requiredInt(resultSet, 2, "format version"),
                    requiredText(resultSet, 3, "game version"),
                    requiredText(resultSet, 4, "language"),
                    requiredText(resultSet, 5, "scope"),
                    requiredText(resultSet, 6, "snapshot ID"),
                    requiredText(resultSet, 7, "content ID"),
                    requiredText(resultSet, 8, "extractor version"),
                    requiredText(resultSet, 9, "Lumina version"),
                    requiredNonNegativeLong(resultSet, 10, "sheet count"),
                    requiredNonNegativeLong(resultSet, 11, "row count"),
                    requiredNonNegativeLong(resultSet, 12, "String cell count"));
            if (resultSet.next()) {
                throw schemaError("hxs_meta must contain exactly one record");
            }
            return metadata;
        }
    }

    private static void readSheets(Connection connection, HxsSourceSink sink) throws SQLException {
        try (Statement statement = forwardOnlyStatement(connection, FETCH_SIZE);
             ResultSet resultSet = statement.executeQuery("""
                     SELECT id, name, variant, effective_language, column_count, row_count,
                            schema_hash, technical_hash, string_hash, content_hash
                     FROM sheets
                     ORDER BY id
                     """)) {
            while (resultSet.next()) {
                long sheetId = requiredNonNegativeLong(resultSet, 1, "sheet ID");
                HxsSheet sheet = new HxsSheet(
                        requiredText(resultSet, 2, "sheet name"),
                        requiredInt(resultSet, 3, "sheet variant"),
                        requiredText(resultSet, 4, "effective language"),
                        requiredNonNegativeLong(resultSet, 5, "sheet column count"),
                        requiredNonNegativeLong(resultSet, 6, "sheet row count"),
                        requiredHash(resultSet, 7, "schema hash"),
                        requiredHash(resultSet, 8, "technical hash"),
                        requiredHash(resultSet, 9, "string hash"),
                        requiredHash(resultSet, 10, "content hash"));
                sink.beginSheet(sheet);
                readColumns(connection, sheetId, sink);
                readRows(connection, sheetId, sink);
                readStringCells(connection, sheetId, sink);
                sink.endSheet();
            }
        }
    }

    private static void readColumns(Connection connection, long sheetId, HxsSourceSink sink)
            throws SQLException {
        try (PreparedStatement statement = prepared(connection, """
                SELECT column_index, offset, type
                FROM columns
                WHERE sheet_id = ?
                ORDER BY column_index
                """, FETCH_SIZE)) {
            statement.setLong(1, sheetId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    sink.column(new HxsColumn(
                            requiredInt(resultSet, 1, "column index"),
                            requiredNonNegativeLong(resultSet, 2, "column offset"),
                            requiredInt(resultSet, 3, "column type")));
                }
            }
        }
    }

    private static void readRows(Connection connection, long sheetId, HxsSourceSink sink)
            throws SQLException {
        try (PreparedStatement statement = prepared(connection, """
                SELECT row_id, subrow_id, row_hash, technical_hash, string_hash
                FROM "rows"
                WHERE sheet_id = ?
                ORDER BY row_id, subrow_id
                """, FETCH_SIZE)) {
            statement.setLong(1, sheetId);
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

    private static void readStringCells(Connection connection, long sheetId, HxsSourceSink sink)
            throws SQLException {
        try (PreparedStatement statement = prepared(connection, """
                SELECT row_id, subrow_id, column_index, macro_text, macro_hash, raw_hash
                FROM string_cells
                WHERE sheet_id = ?
                ORDER BY row_id, subrow_id, column_index
                """, FETCH_SIZE)) {
            statement.setLong(1, sheetId);
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

    private static Statement forwardOnlyStatement(Connection connection, int fetchSize)
            throws SQLException {
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
        byte[] value = resultSet.getBytes(index);
        return value == null ? null : hashBytes(value, field);
    }

    private static byte[] requiredHash(ResultSet resultSet, int index, String field) throws SQLException {
        byte[] value = resultSet.getBytes(index);
        if (value == null) {
            throw schemaError(field + " is null");
        }
        return hashBytes(value, field);
    }

    private static byte[] hashBytes(byte[] value, String field) {
        if (value.length != 32) {
            throw schemaError(field + " must be exactly 32 raw bytes");
        }
        return value;
    }

    private static String requiredText(ResultSet resultSet, int index, String field) throws SQLException {
        String value = resultSet.getString(index);
        if (value == null || value.isBlank()) {
            throw schemaError(field + " is blank");
        }
        return value;
    }

    private static int requiredInt(ResultSet resultSet, int index, String field) throws SQLException {
        int value = resultSet.getInt(index);
        if (resultSet.wasNull()) {
            throw schemaError(field + " is null");
        }
        return value;
    }

    private static long requiredLong(ResultSet resultSet, int index, String field) throws SQLException {
        long value = resultSet.getLong(index);
        if (resultSet.wasNull()) {
            throw schemaError(field + " is null");
        }
        return value;
    }

    private static long requiredNonNegativeLong(ResultSet resultSet, int index, String field)
            throws SQLException {
        long value = requiredLong(resultSet, index, field);
        if (value < 0) {
            throw schemaError(field + " must be non-negative");
        }
        return value;
    }

    private static HxsReadException schemaError(String message) {
        return new HxsReadException(HxsReadException.Reason.SCHEMA, message);
    }
}
