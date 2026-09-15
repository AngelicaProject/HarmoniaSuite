package com.harmoniasuite.source.hxs;

import com.harmoniasuite.db.SqliteDataSources;
import com.harmoniasuite.source.atlas.AtlasInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HxsSourceReaderTest {

    private static final String SNAPSHOT_ID = "sha256:" + "a".repeat(64);
    private static final String CONTENT_ID = "sha256:" + "b".repeat(64);
    private static final AtlasInspection INSPECTION = new AtlasInspection(
            1, "7.2.0", "en", "full", SNAPSHOT_ID, CONTENT_ID,
            "extractor-test", "lumina-test", 1, 2, 1);

    @TempDir
    Path directory;

    private Path hxsPath;

    @BeforeEach
    void setUp() throws Exception {
        hxsPath = directory.resolve("fixture.hxs");
        JdbcTemplate jdbc = new JdbcTemplate(SqliteDataSources.create(hxsPath));
        jdbc.execute("""
                CREATE TABLE hxs_meta (
                    hxs_version INTEGER, game_version TEXT, language TEXT, scope TEXT,
                    snapshot_id TEXT, content_id TEXT, extractor_version TEXT,
                    lumina_version TEXT, sheet_count INTEGER, row_count INTEGER,
                    string_cell_count INTEGER
                )
                """);
        jdbc.execute("""
                CREATE TABLE hxs_sheets (
                    name TEXT, variant INTEGER, effective_language TEXT,
                    column_count INTEGER, row_count INTEGER,
                    schema_hash BLOB, technical_hash BLOB, string_hash BLOB, content_hash BLOB
                )
                """);
        jdbc.execute("CREATE TABLE hxs_columns (sheet_name TEXT, column_index INTEGER, offset INTEGER, type TEXT)");
        jdbc.execute("""
                CREATE TABLE hxs_rows (
                    sheet_name TEXT, row_id INTEGER, subrow_id INTEGER,
                    row_hash BLOB, technical_hash BLOB, string_hash BLOB,
                    technical_payload BLOB
                )
                """);
        jdbc.execute("""
                CREATE TABLE hxs_string_cells (
                    sheet_name TEXT, row_id INTEGER, subrow_id INTEGER, column_index INTEGER,
                    macro_text TEXT, macro_hash BLOB, raw_hash BLOB, raw_value BLOB
                )
                """);
        jdbc.update("""
                INSERT INTO hxs_meta VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, 1, "7.2.0", "en", "full", SNAPSHOT_ID, CONTENT_ID,
                "extractor-test", "lumina-test", 1, 2, 1);
        byte[] sheetHash = hash(9);
        jdbc.update("INSERT INTO hxs_sheets VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "Quest", 0, "en", 1, 2, sheetHash, hash(10), hash(11), hash(12));
        jdbc.update("INSERT INTO hxs_columns VALUES (?, ?, ?, ?)", "Quest", 3, 16, "String");
        jdbc.update("INSERT INTO hxs_rows VALUES (?, ?, ?, ?, ?, ?, ?)",
                "Quest", 7, 0, hash(20), hash(21), hash(22), new byte[]{1, 2, 3});
        jdbc.update("INSERT INTO hxs_rows VALUES (?, ?, ?, ?, ?, ?, ?)",
                "Quest", 7, 1, hash(30), hash(31), hash(32), new byte[]{4, 5, 6});
        jdbc.update("INSERT INTO hxs_string_cells VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "Quest", 7, 1, 3, "{utf8}Hello", hash(40), null, new byte[]{8, 9});
    }

    @Test
    void metadataMismatchFailsBeforeSourceCallbacks() {
        AtlasInspection mismatch = new AtlasInspection(
                1, "7.2.1", "en", "full", SNAPSHOT_ID, CONTENT_ID,
                "extractor-test", "lumina-test", 1, 2, 1);
        List<String> events = new ArrayList<>();

        HxsReadException exception = assertThrows(HxsReadException.class,
                () -> new HxsSourceReader().read(hxsPath, mismatch, new HxsSourceSink() {
                    @Override
                    public boolean begin(HxsMetadata metadata) {
                        events.add("begin");
                        return true;
                    }
                }));

        assertEquals(HxsReadException.Reason.METADATA_MISMATCH, exception.getReason());
        assertEquals(List.of(), events);
    }

    @Test
    void streamsSheetRecordsAndPreservesCoordinatesAndHashes() {
        List<HxsRow> rows = new ArrayList<>();
        List<HxsStringCell> cells = new ArrayList<>();
        new HxsSourceReader().read(hxsPath, INSPECTION, new HxsSourceSink() {
            @Override
            public void row(HxsRow row) {
                rows.add(row);
            }

            @Override
            public void stringCell(HxsStringCell cell) {
                cells.add(cell);
            }
        });

        assertEquals(2, rows.size());
        assertEquals(7, rows.get(0).rowId());
        assertEquals(0, rows.get(0).subrowId());
        assertArrayEquals(hash(21), rows.get(0).technicalHash());
        assertEquals(1, cells.size());
        assertEquals(3, cells.get(0).columnIndex());
        assertEquals("{utf8}Hello", cells.get(0).macroText());
        assertArrayEquals(hash(40), cells.get(0).macroHash());
        assertEquals(null, cells.get(0).rawHash());
    }

    @Test
    void opensArtifactStrictlyReadOnly() throws Exception {
        HxsSourceReader reader = new HxsSourceReader();
        try (Connection connection = reader.openReadOnlyConnection(hxsPath)) {
            assertThrows(SQLException.class, () -> connection.createStatement()
                    .executeUpdate("CREATE TABLE should_not_exist (value TEXT)"));
            assertThrows(SQLException.class, () -> connection.prepareStatement(
                    "INSERT INTO hxs_meta VALUES (1, 'x', 'en', 'full', 'x', 'x', 'x', 'x', 0, 0, 0)"
            ).executeUpdate());
        }
    }

    private static byte[] hash(int seed) {
        byte[] value = new byte[32];
        for (int index = 0; index < value.length; index++) {
            value[index] = (byte) (seed + index);
        }
        return value;
    }
}
