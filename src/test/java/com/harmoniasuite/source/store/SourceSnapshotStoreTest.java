package com.harmoniasuite.source.store;

import com.harmoniasuite.TestDatabases;
import com.harmoniasuite.db.SqliteDataSources;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmoniasuite.source.atlas.AtlasClient;
import com.harmoniasuite.source.atlas.AtlasProcessResult;
import com.harmoniasuite.source.atlas.AtlasProperties;
import com.harmoniasuite.source.atlas.AtlasInspection;
import com.harmoniasuite.source.hxs.HxsSourceReader;
import com.harmoniasuite.source.hxs.HxsSourceSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceSnapshotStoreTest {

    private static final String SNAPSHOT_ID = "sha256:" + "c".repeat(64);
    private static final String CONTENT_ID = "sha256:" + "d".repeat(64);
    private static final AtlasInspection INSPECTION = new AtlasInspection(
            1, "7.2.0", "en", "full", SNAPSHOT_ID, CONTENT_ID,
            "extractor-test", "lumina-test", 2, 3, 3);

    @TempDir
    Path directory;

    private JdbcTemplate core;
    private Path hxsPath;

    @BeforeEach
    void setUp() throws Exception {
        core = TestDatabases.coreSqlite(directory.resolve("core"));
        hxsPath = directory.resolve("source.hxs");
        JdbcTemplate hxs = new JdbcTemplate(SqliteDataSources.create(hxsPath));
        createHxs(hxs);
    }

    @Test
    void importsSourceDomainAndSupportsRuntimeLookup() {
        JdbcSourceSnapshotStore store = importStore();

        SourceSnapshot snapshot = store.findBySnapshotId(SNAPSHOT_ID).orElseThrow();
        assertEquals(CONTENT_ID, snapshot.contentId());
        assertEquals(2, snapshot.sheetCount());
        assertEquals(3, snapshot.rowCount());
        assertEquals(3, snapshot.stringCellCount());
        assertEquals(2, store.findSheets(SNAPSHOT_ID).size());
        SourceSheet sheet = store.findSheet(SNAPSHOT_ID, "Quest").orElseThrow();
        assertEquals(0, sheet.variant());
        assertArrayEquals(hash(9), sheet.schemaHash());
        assertArrayEquals(hash(10), sheet.technicalHash());
        assertEquals(16L, core.queryForObject(
                "SELECT offset FROM source_columns WHERE sheet_id = ? AND column_index = ?",
                Long.class, sheet.id(), 3));
        assertEquals(1, core.queryForObject(
                "SELECT type FROM source_columns WHERE sheet_id = ? AND column_index = ?",
                Integer.class, sheet.id(), 3));
        assertEquals(3, store.countRows(SNAPSHOT_ID));
        assertEquals(3, store.countStringCells(SNAPSHOT_ID));
        SourceStringCell cell = store.findStringCell(SNAPSHOT_ID, "Quest", 7, 1, 4).orElseThrow();
        assertEquals("{utf8}Quest subrow", cell.macroText());
        assertArrayEquals(hash(44), cell.macroHash());
        assertNull(cell.rawHash());
        assertEquals(0, core.queryForObject("SELECT COUNT(*) FROM source_string_cells WHERE macro_text = ?",
                Integer.class, "{utf8}Quest raw value"));
    }

    @Test
    void duplicateImportIsIdempotentAndDoesNotDuplicateChildren() {
        JdbcSourceSnapshotStore store = importStore();

        SourceSnapshot first = store.findBySnapshotId(SNAPSHOT_ID).orElseThrow();
        SourceSnapshot second = store.importSnapshot(hxsPath, INSPECTION, new HxsSourceReader());

        assertEquals(first.id(), second.id());
        assertEquals(1, core.queryForObject("SELECT COUNT(*) FROM source_snapshots", Integer.class));
        assertEquals(2, core.queryForObject("SELECT COUNT(*) FROM source_sheets", Integer.class));
        assertEquals(3, core.queryForObject("SELECT COUNT(*) FROM source_rows", Integer.class));
        assertEquals(3, core.queryForObject("SELECT COUNT(*) FROM source_string_cells", Integer.class));
    }

    @Test
    void conflictingStoredMetadataFailsWithoutOverwrite() {
        JdbcSourceSnapshotStore store = importStore();
        core.update("UPDATE source_snapshots SET game_version = '7.2.1' WHERE snapshot_id = ?", SNAPSHOT_ID);

        assertThrows(SourceSnapshotImportException.class,
                () -> store.importSnapshot(hxsPath, INSPECTION, new HxsSourceReader()));
        assertEquals("7.2.1", core.queryForObject("SELECT game_version FROM source_snapshots WHERE snapshot_id = ?",
                String.class, SNAPSHOT_ID));
    }

    @Test
    void globalCountMismatchRollsBackAllTables() throws Exception {
        new JdbcTemplate(SqliteDataSources.create(hxsPath))
                .update("UPDATE hxs_meta SET row_count = 4");
        AtlasInspection mismatch = new AtlasInspection(
                1, "7.2.0", "en", "full", SNAPSHOT_ID, CONTENT_ID,
                "extractor-test", "lumina-test", 2, 4, 3);
        JdbcSourceSnapshotStore store = new JdbcSourceSnapshotStore(core);

        assertThrows(SourceSnapshotImportException.class,
                () -> store.importSnapshot(hxsPath, mismatch, new HxsSourceReader()));
        assertNoPartialImport();
    }

    @Test
    void globalSheetCountMismatchRollsBackAllTables() throws Exception {
        new JdbcTemplate(SqliteDataSources.create(hxsPath))
                .update("UPDATE hxs_meta SET sheet_count = 3");
        AtlasInspection mismatch = inspectionWithCounts(3, 3, 3);
        JdbcSourceSnapshotStore store = new JdbcSourceSnapshotStore(core);

        assertThrows(SourceSnapshotImportException.class,
                () -> store.importSnapshot(hxsPath, mismatch, new HxsSourceReader()));
        assertNoPartialImport();
    }

    @Test
    void globalStringCellCountMismatchRollsBackAllTables() throws Exception {
        new JdbcTemplate(SqliteDataSources.create(hxsPath))
                .update("UPDATE hxs_meta SET string_cell_count = 4");
        AtlasInspection mismatch = inspectionWithCounts(2, 3, 4);
        JdbcSourceSnapshotStore store = new JdbcSourceSnapshotStore(core);

        assertThrows(SourceSnapshotImportException.class,
                () -> store.importSnapshot(hxsPath, mismatch, new HxsSourceReader()));
        assertNoPartialImport();
    }

    @Test
    void perSheetColumnCountMismatchRollsBackAllTables() throws Exception {
        new JdbcTemplate(SqliteDataSources.create(hxsPath))
                .update("UPDATE sheets SET column_count = 3 WHERE name = 'Quest'");
        JdbcSourceSnapshotStore store = new JdbcSourceSnapshotStore(core);

        assertThrows(SourceSnapshotImportException.class,
                () -> store.importSnapshot(hxsPath, INSPECTION, new HxsSourceReader()));
        assertNoPartialImport();
    }

    @Test
    void perSheetRowCountMismatchRollsBackAllTables() throws Exception {
        new JdbcTemplate(SqliteDataSources.create(hxsPath))
                .update("UPDATE sheets SET row_count = 3 WHERE name = 'Quest'");
        JdbcSourceSnapshotStore store = new JdbcSourceSnapshotStore(core);

        assertThrows(SourceSnapshotImportException.class,
                () -> store.importSnapshot(hxsPath, INSPECTION, new HxsSourceReader()));
        assertNoPartialImport();
    }

    @Test
    void failureDuringSheetImportRollsBackAllTables() {
        JdbcSourceSnapshotStore store = new JdbcSourceSnapshotStore(core);
        HxsSourceReader failingReader = new HxsSourceReader() {
            @Override
            public void read(Path path, AtlasInspection inspection,
                             com.harmoniasuite.source.hxs.HxsSourceSink sink) {
                sink.begin(new com.harmoniasuite.source.hxs.HxsMetadata(
                        inspection.hxsVersion(), inspection.gameVersion(), inspection.language(),
                        inspection.scope(), inspection.snapshotId(), inspection.contentId(),
                        inspection.extractorVersion(), inspection.luminaVersion(),
                        inspection.sheetCount(), inspection.rowCount(), inspection.stringCellCount()));
                throw new IllegalStateException("synthetic failure");
            }
        };

        assertThrows(IllegalStateException.class,
                () -> store.importSnapshot(hxsPath, INSPECTION, failingReader));
        assertNoPartialImport();
    }

    @Test
    void schemaStoresBinaryHashesAndNotTechnicalPayloadOrRawValue() {
        importStore();

        assertEquals("blob", core.queryForObject("SELECT typeof(schema_hash) FROM source_sheets LIMIT 1",
                String.class));
        assertEquals("blob", core.queryForObject("SELECT typeof(macro_hash) FROM source_string_cells LIMIT 1",
                String.class));
        assertEquals("integer", core.queryForObject("SELECT typeof(type) FROM source_columns LIMIT 1",
                String.class));
        assertEquals(0, core.queryForObject("SELECT COUNT(*) FROM source_snapshots WHERE snapshot_id LIKE '%path%'",
                Integer.class));
        assertTrue(Arrays.stream(core.queryForList("PRAGMA table_info(source_rows)").toString().split(","))
                .noneMatch(value -> value.contains("technical_payload")));
        assertTrue(core.queryForList("PRAGMA table_info(source_string_cells)").stream()
                .noneMatch(value -> "raw_value".equals(value.get("name"))));
    }

    @Test
    void applicationImporterInspectsBeforeReading() {
        List<String> events = new ArrayList<>();
        AtlasClient atlas = new AtlasClient(new AtlasProperties(),
                (arguments, timeout, maxStdoutBytes, maxStderrBytes) ->
                        new AtlasProcessResult(0, "", ""), new ObjectMapper()) {
            @Override
            public AtlasInspection inspect(Path path) {
                events.add("atlas");
                return INSPECTION;
            }
        };
        HxsSourceReader reader = new HxsSourceReader() {
            @Override
            public void read(Path path, AtlasInspection inspection, HxsSourceSink sink) {
                events.add("reader");
                super.read(path, inspection, sink);
            }
        };

        new SourceSnapshotImporter(atlas, reader, new JdbcSourceSnapshotStore(core))
                .importSnapshot(hxsPath);

        assertEquals(List.of("atlas", "reader"), events);
    }

    @Test
    void concurrentDuplicateRegistrationLeavesOneCanonicalSnapshot() throws Exception {
        JdbcSourceSnapshotStore firstStore = new JdbcSourceSnapshotStore(core);
        JdbcSourceSnapshotStore secondStore = new JdbcSourceSnapshotStore(core);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<SourceSnapshot> first = executor.submit(() -> importAtStart(
                    firstStore, hxsPath, ready, start));
            Future<SourceSnapshot> second = executor.submit(() -> importAtStart(
                    secondStore, hxsPath, ready, start));
            ready.await();
            start.countDown();

            SourceSnapshot firstResult = first.get();
            SourceSnapshot secondResult = second.get();
            assertEquals(firstResult.id(), secondResult.id());
            assertEquals(1, core.queryForObject("SELECT COUNT(*) FROM source_snapshots", Integer.class));
            assertEquals(2, core.queryForObject("SELECT COUNT(*) FROM source_sheets", Integer.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void largeSyntheticFixtureCrossesBoundedBatchBoundary() throws Exception {
        int count = 2_001;
        String snapshotId = "sha256:" + "e".repeat(64);
        String contentId = "sha256:" + "f".repeat(64);
        Path largeHxsPath = directory.resolve("large-source.hxs");
        JdbcTemplate hxs = new JdbcTemplate(SqliteDataSources.create(largeHxsPath));
        createLargeHxs(hxs, count, snapshotId, contentId);
        AtlasInspection inspection = new AtlasInspection(
                1, "7.2.0", "en", "full", snapshotId, contentId,
                "extractor-test", "lumina-test", 1, count, count);

        JdbcSourceSnapshotStore store = new JdbcSourceSnapshotStore(core);
        store.importSnapshot(largeHxsPath, inspection, new HxsSourceReader());

        assertEquals(count, store.countRows(snapshotId));
        assertEquals(count, store.countStringCells(snapshotId));
        assertEquals("{utf8}row-2000", store.findStringCell(snapshotId, "Large", 2_000, 0, 1)
                .orElseThrow().macroText());
    }

    private static SourceSnapshot importAtStart(JdbcSourceSnapshotStore store, Path hxsPath,
                                                CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        start.await();
        return store.importSnapshot(hxsPath, INSPECTION,
                new HxsSourceReader());
    }

    private JdbcSourceSnapshotStore importStore() {
        JdbcSourceSnapshotStore store = new JdbcSourceSnapshotStore(core);
        store.importSnapshot(hxsPath, INSPECTION, new HxsSourceReader());
        return store;
    }

    private void assertNoPartialImport() {
        assertEquals(0, core.queryForObject("SELECT COUNT(*) FROM source_snapshots", Integer.class));
        assertEquals(0, core.queryForObject("SELECT COUNT(*) FROM source_sheets", Integer.class));
        assertEquals(0, core.queryForObject("SELECT COUNT(*) FROM source_rows", Integer.class));
        assertEquals(0, core.queryForObject("SELECT COUNT(*) FROM source_string_cells", Integer.class));
    }

    private static void createHxs(JdbcTemplate hxs) {
        hxs.execute("""
                CREATE TABLE hxs_meta (
                    id INTEGER PRIMARY KEY, format_version INTEGER NOT NULL,
                    game_version TEXT NOT NULL, language TEXT NOT NULL, scope TEXT NOT NULL,
                    content_id TEXT NOT NULL, snapshot_id TEXT NOT NULL,
                    extractor_version TEXT NOT NULL, lumina_version TEXT NOT NULL,
                    sheet_count INTEGER NOT NULL, row_count INTEGER NOT NULL,
                    string_cell_count INTEGER NOT NULL
                )
                """);
        hxs.execute("""
                CREATE TABLE sheets (
                    id INTEGER PRIMARY KEY, name TEXT NOT NULL, variant INTEGER NOT NULL,
                    effective_language TEXT NOT NULL, column_count INTEGER NOT NULL,
                    row_count INTEGER NOT NULL, schema_hash BLOB NOT NULL,
                    technical_hash BLOB NOT NULL, string_hash BLOB NOT NULL,
                    content_hash BLOB NOT NULL
                )
                """);
        hxs.execute("CREATE TABLE columns (sheet_id INTEGER, column_index INTEGER, offset INTEGER, type INTEGER)");
        hxs.execute("""
                CREATE TABLE "rows" (
                    sheet_id INTEGER, row_id INTEGER, subrow_id INTEGER,
                    row_hash BLOB, technical_hash BLOB, string_hash BLOB, technical_payload BLOB
                )
                """);
        hxs.execute("""
                CREATE TABLE string_cells (
                    sheet_id INTEGER, row_id INTEGER, subrow_id INTEGER, column_index INTEGER,
                    macro_text TEXT, macro_hash BLOB, raw_hash BLOB, raw_value BLOB
                )
                """);
        hxs.update("INSERT INTO hxs_meta VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1, 1, "7.2.0", "en", "full", CONTENT_ID, SNAPSHOT_ID,
                "extractor-test", "lumina-test", 2, 3, 3);
        hxs.update("INSERT INTO sheets VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                17, "Quest", 0, "en", 2, 2, hash(9), hash(10), hash(11), hash(12));
        hxs.update("INSERT INTO sheets VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                23, "Subrow", 1, "en", 1, 1, hash(19), hash(20), hash(21), hash(22));
        hxs.update("INSERT INTO columns VALUES (?, ?, ?, ?)", 17, 3, 16, 1);
        hxs.update("INSERT INTO columns VALUES (?, ?, ?, ?)", 17, 4, 24, 1);
        hxs.update("INSERT INTO columns VALUES (?, ?, ?, ?)", 23, 1, 8, 1);
        hxs.update("INSERT INTO \"rows\" VALUES (?, ?, ?, ?, ?, ?, ?)",
                17, 7, 0, hash(30), hash(31), hash(32), new byte[]{1});
        hxs.update("INSERT INTO \"rows\" VALUES (?, ?, ?, ?, ?, ?, ?)",
                17, 7, 1, hash(33), hash(34), hash(35), new byte[]{2});
        hxs.update("INSERT INTO \"rows\" VALUES (?, ?, ?, ?, ?, ?, ?)",
                23, 9, 0, hash(36), hash(37), hash(38), new byte[]{3});
        hxs.update("INSERT INTO string_cells VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                17, 7, 0, 3, "{utf8}Quest text", hash(42), hash(43), new byte[]{4});
        hxs.update("INSERT INTO string_cells VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                17, 7, 1, 4, "{utf8}Quest subrow", hash(44), null, new byte[]{5});
        hxs.update("INSERT INTO string_cells VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                23, 9, 0, 1, "{utf8}Subrow text", hash(45), hash(46), new byte[]{6});
    }

    private static void createLargeHxs(JdbcTemplate hxs, int count, String snapshotId,
                                       String contentId) {
        hxs.execute("""
                CREATE TABLE hxs_meta (
                    id INTEGER PRIMARY KEY, format_version INTEGER NOT NULL,
                    game_version TEXT NOT NULL, language TEXT NOT NULL, scope TEXT NOT NULL,
                    content_id TEXT NOT NULL, snapshot_id TEXT NOT NULL,
                    extractor_version TEXT NOT NULL, lumina_version TEXT NOT NULL,
                    sheet_count INTEGER NOT NULL, row_count INTEGER NOT NULL,
                    string_cell_count INTEGER NOT NULL
                )
                """);
        hxs.execute("""
                CREATE TABLE sheets (
                    id INTEGER PRIMARY KEY, name TEXT NOT NULL, variant INTEGER NOT NULL,
                    effective_language TEXT NOT NULL, column_count INTEGER NOT NULL,
                    row_count INTEGER NOT NULL, schema_hash BLOB NOT NULL,
                    technical_hash BLOB NOT NULL, string_hash BLOB NOT NULL,
                    content_hash BLOB NOT NULL
                )
                """);
        hxs.execute("CREATE TABLE columns (sheet_id INTEGER, column_index INTEGER, offset INTEGER, type INTEGER)");
        hxs.execute("""
                CREATE TABLE "rows" (
                    sheet_id INTEGER, row_id INTEGER, subrow_id INTEGER,
                    row_hash BLOB, technical_hash BLOB, string_hash BLOB, technical_payload BLOB
                )
                """);
        hxs.execute("""
                CREATE TABLE string_cells (
                    sheet_id INTEGER, row_id INTEGER, subrow_id INTEGER, column_index INTEGER,
                    macro_text TEXT, macro_hash BLOB, raw_hash BLOB, raw_value BLOB
                )
                """);
        hxs.update("INSERT INTO hxs_meta VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1, 1, "7.2.0", "en", "full", contentId, snapshotId,
                "extractor-test", "lumina-test", 1, count, count);
        hxs.update("INSERT INTO sheets VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                31, "Large", 0, "en", 1, count, hash(50), hash(51), hash(52), hash(53));
        hxs.update("INSERT INTO columns VALUES (?, ?, ?, ?)", 31, 1, 8, 1);

        List<Object[]> rows = new ArrayList<>(count);
        List<Object[]> cells = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            rows.add(new Object[]{31, index, 0, hash(index), hash(index + 1), hash(index + 2),
                    new byte[]{1}});
            cells.add(new Object[]{31, index, 0, 1, "{utf8}row-" + index, hash(index + 3), null,
                    new byte[]{2}});
        }
        hxs.batchUpdate("INSERT INTO \"rows\" VALUES (?, ?, ?, ?, ?, ?, ?)", rows);
        hxs.batchUpdate("INSERT INTO string_cells VALUES (?, ?, ?, ?, ?, ?, ?, ?)", cells);
    }

    private static AtlasInspection inspectionWithCounts(long sheetCount, long rowCount,
                                                        long stringCellCount) {
        return new AtlasInspection(1, "7.2.0", "en", "full", SNAPSHOT_ID, CONTENT_ID,
                "extractor-test", "lumina-test", sheetCount, rowCount, stringCellCount);
    }

    private static byte[] hash(int seed) {
        byte[] value = new byte[32];
        for (int index = 0; index < value.length; index++) {
            value[index] = (byte) (seed + index);
        }
        return value;
    }
}
