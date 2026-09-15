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
                    id INTEGER PRIMARY KEY, format_version INTEGER NOT NULL,
                    game_version TEXT NOT NULL, language TEXT NOT NULL, scope TEXT NOT NULL,
                    content_id TEXT NOT NULL, snapshot_id TEXT NOT NULL,
                    extractor_version TEXT NOT NULL, lumina_version TEXT NOT NULL,
                    sheet_count INTEGER NOT NULL, row_count INTEGER NOT NULL,
                    string_cell_count INTEGER NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE sheets (
                    id INTEGER PRIMARY KEY, name TEXT NOT NULL, variant INTEGER NOT NULL,
                    effective_language TEXT NOT NULL, column_count INTEGER NOT NULL,
                    row_count INTEGER NOT NULL, schema_hash BLOB NOT NULL,
                    technical_hash BLOB NOT NULL, string_hash BLOB NOT NULL,
                    content_hash BLOB NOT NULL
                )
                """);
        jdbc.execute("CREATE TABLE columns (sheet_id INTEGER, column_index INTEGER, offset INTEGER, type INTEGER)");
        jdbc.execute("""
                CREATE TABLE "rows" (
                    sheet_id INTEGER, row_id INTEGER, subrow_id INTEGER,
                    row_hash BLOB, technical_hash BLOB, string_hash BLOB,
                    technical_payload BLOB
                )
                """);
        jdbc.execute("""
                CREATE TABLE string_cells (
                    sheet_id INTEGER, row_id INTEGER, subrow_id INTEGER, column_index INTEGER,
                    macro_text TEXT, macro_hash BLOB, raw_hash BLOB, raw_value BLOB
                )
                """);
        jdbc.update("""
                INSERT INTO hxs_meta VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, 1, 1, "7.2.0", "en", "full", CONTENT_ID, SNAPSHOT_ID,
                "extractor-test", "lumina-test", 1, 2, 1);
        byte[] sheetHash = hash(9);
        jdbc.update("INSERT INTO sheets VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                41, "Quest", 0, "en", 1, 2, sheetHash, hash(10), hash(11), hash(12));
        jdbc.update("INSERT INTO columns VALUES (?, ?, ?, ?)", 41, 3, 16, 1);
        jdbc.update("INSERT INTO \"rows\" VALUES (?, ?, ?, ?, ?, ?, ?)",
                41, 7, 0, hash(20), hash(21), hash(22), new byte[]{1, 2, 3});
        jdbc.update("INSERT INTO \"rows\" VALUES (?, ?, ?, ?, ?, ?, ?)",
                41, 7, 1, hash(30), hash(31), hash(32), new byte[]{4, 5, 6});
        jdbc.update("INSERT INTO string_cells VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                41, 7, 1, 3, "{utf8}Hello", hash(40), null, new byte[]{8, 9});
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
        List<HxsSheet> sheets = new ArrayList<>();
        List<HxsColumn> columns = new ArrayList<>();
        List<HxsRow> rows = new ArrayList<>();
        List<HxsStringCell> cells = new ArrayList<>();
        new HxsSourceReader().read(hxsPath, INSPECTION, new HxsSourceSink() {
            @Override
            public void beginSheet(HxsSheet sheet) {
                sheets.add(sheet);
            }

            @Override
            public void column(HxsColumn column) {
                columns.add(column);
            }

            @Override
            public void row(HxsRow row) {
                rows.add(row);
            }

            @Override
            public void stringCell(HxsStringCell cell) {
                cells.add(cell);
            }
        });

        assertEquals(1, sheets.size());
        assertEquals("Quest", sheets.get(0).name());
        assertEquals(1, columns.size());
        assertEquals(3, columns.get(0).columnIndex());
        assertEquals(16, columns.get(0).offset());
        assertEquals(1, columns.get(0).type());
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
                    "INSERT INTO hxs_meta VALUES (2, 1, 'x', 'en', 'full', 'x', 'x', 'x', 'x', 0, 0, 0)"
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
