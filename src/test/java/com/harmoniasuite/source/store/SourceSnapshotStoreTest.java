package com.harmoniasuite.source.infrastructure.persistence;

import com.harmoniasuite.source.domain.*;

import com.harmoniasuite.TestDatabases;
import com.harmoniasuite.db.SqliteDataSources;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmoniasuite.source.infrastructure.atlas.AtlasClient;
import com.harmoniasuite.source.infrastructure.atlas.AtlasProcessResult;
import com.harmoniasuite.source.infrastructure.config.AtlasProperties;
import com.harmoniasuite.source.domain.SourceSnapshotMetadata;
import com.harmoniasuite.source.infrastructure.hxs.HxsSourceReader;
import com.harmoniasuite.source.infrastructure.hxs.HxsSourceSink;
import com.harmoniasuite.source.infrastructure.hxs.HxsV1TestFixture;
import com.harmoniasuite.source.application.SourceSnapshotImportService;
import com.harmoniasuite.source.application.exception.SourceSnapshotImportException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceSnapshotRepositoryTest {

    private static final String SNAPSHOT_ID = "sha256:" + "c".repeat(64);
    private static final String CONTENT_ID = "sha256:" + "d".repeat(64);
    private static final SourceSnapshotMetadata INSPECTION = new SourceSnapshotMetadata(
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
        createHxs(hxsPath);
    }

    @Test
    void importsSourceDomainAndSupportsRuntimeLookup() {
        JdbcSourceSnapshotRepository store = importStore();

        SourceSnapshot snapshot = store.findBySnapshotId(SNAPSHOT_ID).orElseThrow();
        assertEquals(CONTENT_ID, snapshot.contentId());
        assertEquals(2, snapshot.sheetCount());
        assertEquals(3, snapshot.rowCount());
        assertEquals(3, snapshot.stringCellCount());
        assertEquals(2, store.findSheets(SNAPSHOT_ID).size());
        SourceSheet sheet = store.findSheet(SNAPSHOT_ID, "Quest").orElseThrow();
        assertEquals(0, sheet.variant());
        assertArrayEquals(hash(9), sheet.schemaHash().bytes());
        assertArrayEquals(hash(10), sheet.technicalHash().bytes());
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
        assertArrayEquals(hash(44), cell.macroHash().bytes());
        assertNull(cell.rawHash());
        assertEquals(0, core.queryForObject("SELECT COUNT(*) FROM source_string_cells WHERE macro_text = ?",
                Integer.class, "{utf8}Quest raw value"));
    }

    @Test
    void listsSnapshotsInDeterministicMetadataOrder() {
        core.update("""
                INSERT INTO source_snapshots
                    (snapshot_id, content_id, hxs_version, game_version, language, scope,
                     extractor_version, lumina_version, sheet_count, row_count, string_cell_count)
                VALUES (?, ?, 1, ?, ?, 'full', 'extractor-test', 'lumina-test', 0, 0, 0)
                """, "sha256:" + "f".repeat(64), "sha256:" + "0".repeat(64),
                "7.3.0", "en");
        core.update("""
                INSERT INTO source_snapshots
                    (snapshot_id, content_id, hxs_version, game_version, language, scope,
                     extractor_version, lumina_version, sheet_count, row_count, string_cell_count)
                VALUES (?, ?, 1, ?, ?, 'full', 'extractor-test', 'lumina-test', 0, 0, 0)
                """, "sha256:" + "e".repeat(64), "sha256:" + "1".repeat(64),
                "7.2.0", "en");
        core.update("""
                INSERT INTO source_snapshots
                    (snapshot_id, content_id, hxs_version, game_version, language, scope,
                     extractor_version, lumina_version, sheet_count, row_count, string_cell_count)
                VALUES (?, ?, 1, ?, ?, 'full', 'extractor-test', 'lumina-test', 0, 0, 0)
                """, "sha256:" + "d".repeat(64), "sha256:" + "2".repeat(64),
                "7.2.0", "ja");

        List<SourceSnapshot> snapshots = new JdbcSourceSnapshotRepository(core).listSnapshots();

        assertEquals(List.of("7.2.0/en", "7.2.0/ja", "7.3.0/en"), snapshots.stream()
                .map(snapshot -> snapshot.gameVersion() + "/" + snapshot.language())
                .toList());
    }

    @Test
    void importPreservesEmptyMacroTextExactly() throws Exception {
        new JdbcTemplate(SqliteDataSources.create(hxsPath)).update("""
                UPDATE string_cells
                SET macro_text = ''
                WHERE sheet_id = ? AND row_id = ? AND subrow_id = ? AND column_index = ?
                """, 17, 7, 0, 3);

        importStore();

        assertEquals("", core.queryForObject("""
                SELECT macro_text
                FROM source_string_cells
                WHERE sheet_id = (SELECT id FROM source_sheets WHERE name = 'Quest')
                  AND row_id = ? AND subrow_id = ? AND column_index = ?
                """, String.class, 7, 0, 3));
    }

    @Test
    void trustedStoreImportSeamIsNotPublic() throws NoSuchMethodException {
        Method importMethod = JdbcSourceSnapshotRepository.class.getDeclaredMethod(
                "importSnapshot", Path.class, SourceSnapshotMetadata.class, HxsSourceReader.class);

        assertFalse(Modifier.isPublic(importMethod.getModifiers()));
    }

    @Test
    void duplicateImportIsIdempotentAndDoesNotDuplicateChildren() {
        JdbcSourceSnapshotRepository store = importStore();

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
        JdbcSourceSnapshotRepository store = importStore();
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
        SourceSnapshotMetadata mismatch = new SourceSnapshotMetadata(
                1, "7.2.0", "en", "full", SNAPSHOT_ID, CONTENT_ID,
                "extractor-test", "lumina-test", 2, 4, 3);
        JdbcSourceSnapshotRepository store = new JdbcSourceSnapshotRepository(core);

        assertThrows(SourceSnapshotImportException.class,
                () -> store.importSnapshot(hxsPath, mismatch, new HxsSourceReader()));
        assertNoPartialImport();
    }

    @Test
    void globalSheetCountMismatchRollsBackAllTables() throws Exception {
        new JdbcTemplate(SqliteDataSources.create(hxsPath))
                .update("UPDATE hxs_meta SET sheet_count = 3");
        SourceSnapshotMetadata mismatch = inspectionWithCounts(3, 3, 3);
        JdbcSourceSnapshotRepository store = new JdbcSourceSnapshotRepository(core);

        assertThrows(SourceSnapshotImportException.class,
                () -> store.importSnapshot(hxsPath, mismatch, new HxsSourceReader()));
        assertNoPartialImport();
    }

    @Test
    void globalStringCellCountMismatchRollsBackAllTables() throws Exception {
        new JdbcTemplate(SqliteDataSources.create(hxsPath))
                .update("UPDATE hxs_meta SET string_cell_count = 4");
        SourceSnapshotMetadata mismatch = inspectionWithCounts(2, 3, 4);
        JdbcSourceSnapshotRepository store = new JdbcSourceSnapshotRepository(core);

        assertThrows(SourceSnapshotImportException.class,
                () -> store.importSnapshot(hxsPath, mismatch, new HxsSourceReader()));
        assertNoPartialImport();
    }

    @Test
    void perSheetColumnCountMismatchRollsBackAllTables() throws Exception {
        new JdbcTemplate(SqliteDataSources.create(hxsPath))
                .update("UPDATE sheets SET column_count = 3 WHERE name = 'Quest'");
        JdbcSourceSnapshotRepository store = new JdbcSourceSnapshotRepository(core);

        assertThrows(SourceSnapshotImportException.class,
                () -> store.importSnapshot(hxsPath, INSPECTION, new HxsSourceReader()));
        assertNoPartialImport();
    }

    @Test
    void perSheetRowCountMismatchRollsBackAllTables() throws Exception {
        new JdbcTemplate(SqliteDataSources.create(hxsPath))
                .update("UPDATE sheets SET row_count = 3 WHERE name = 'Quest'");
        JdbcSourceSnapshotRepository store = new JdbcSourceSnapshotRepository(core);

        assertThrows(SourceSnapshotImportException.class,
                () -> store.importSnapshot(hxsPath, INSPECTION, new HxsSourceReader()));
        assertNoPartialImport();
    }

    @Test
    void failureDuringSheetImportRollsBackAllTables() {
        JdbcSourceSnapshotRepository store = new JdbcSourceSnapshotRepository(core);
        HxsSourceReader failingReader = new HxsSourceReader() {
            @Override
            public void read(Path path, SourceSnapshotMetadata inspection,
                             com.harmoniasuite.source.infrastructure.hxs.HxsSourceSink sink) {
                sink.begin(new com.harmoniasuite.source.infrastructure.hxs.HxsMetadata(
                        inspection.hxsVersion(), inspection.gameVersion(), inspection.language(),
                        inspection.scope(), inspection.snapshotId().value(), inspection.contentId().value(),
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
            public SourceSnapshotMetadata inspect(Path path) {
                events.add("atlas");
                return INSPECTION;
            }
        };
        HxsSourceReader reader = new HxsSourceReader() {
            @Override
            public void read(Path path, SourceSnapshotMetadata inspection, HxsSourceSink sink) {
                events.add("reader");
                super.read(path, inspection, sink);
            }
        };

        new SourceSnapshotImportService(atlas,
                new com.harmoniasuite.source.infrastructure.persistence.JdbcSourceSnapshotMaterializer(
                        new JdbcSourceSnapshotRepository(core), reader))
                .importSnapshot(hxsPath);

        assertEquals(List.of("atlas", "reader"), events);
    }

    @Test
    void concurrentDuplicateRegistrationLeavesOneCanonicalSnapshot() throws Exception {
        JdbcSourceSnapshotRepository firstStore = new JdbcSourceSnapshotRepository(core);
        JdbcSourceSnapshotRepository secondStore = new JdbcSourceSnapshotRepository(core);
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
        SourceSnapshotMetadata inspection = new SourceSnapshotMetadata(
                1, "7.2.0", "en", "full", snapshotId, contentId,
                "extractor-test", "lumina-test", 1, count, count);
        createLargeHxs(largeHxsPath, inspection);

        JdbcSourceSnapshotRepository store = new JdbcSourceSnapshotRepository(core);
        store.importSnapshot(largeHxsPath, inspection, new HxsSourceReader());

        assertEquals(count, store.countRows(snapshotId));
        assertEquals(count, store.countStringCells(snapshotId));
        assertEquals("{utf8}row-2000", store.findStringCell(snapshotId, "Large", 2_000, 0, 1)
                .orElseThrow().macroText());
    }

    @Test
    void flushesRowsBeforeStringCellBatchForSmallRowDenseStringFixture() throws Exception {
        int rowCount = 100;
        int cellsPerRow = 21;
        int stringCellCount = rowCount * cellsPerRow;
        String snapshotId = "sha256:" + "7".repeat(64);
        String contentId = "sha256:" + "8".repeat(64);
        Path denseHxsPath = directory.resolve("small-rows-large-cells.hxs");
        SourceSnapshotMetadata inspection = new SourceSnapshotMetadata(
                1, "7.2.0", "en", "full", snapshotId, contentId,
                "extractor-test", "lumina-test", 1, rowCount, stringCellCount);
        createSmallRowsLargeCellsHxs(denseHxsPath, inspection, rowCount, cellsPerRow);

        JdbcSourceSnapshotRepository store = new JdbcSourceSnapshotRepository(core);
        store.importSnapshot(denseHxsPath, inspection, new HxsSourceReader());

        assertEquals(rowCount, store.countRows(snapshotId));
        assertEquals(stringCellCount, store.countStringCells(snapshotId));
        assertEquals("{utf8}r99-c21", store.findStringCell(snapshotId, "DenseStrings", 99, 0, 21)
                .orElseThrow().macroText());
    }

    private static SourceSnapshot importAtStart(JdbcSourceSnapshotRepository store, Path hxsPath,
                                                CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        start.await();
        return store.importSnapshot(hxsPath, INSPECTION,
                new HxsSourceReader());
    }

    private JdbcSourceSnapshotRepository importStore() {
        JdbcSourceSnapshotRepository store = new JdbcSourceSnapshotRepository(core);
        store.importSnapshot(hxsPath, INSPECTION, new HxsSourceReader());
        return store;
    }

    private void assertNoPartialImport() {
        assertEquals(0, core.queryForObject("SELECT COUNT(*) FROM source_snapshots", Integer.class));
        assertEquals(0, core.queryForObject("SELECT COUNT(*) FROM source_sheets", Integer.class));
        assertEquals(0, core.queryForObject("SELECT COUNT(*) FROM source_rows", Integer.class));
        assertEquals(0, core.queryForObject("SELECT COUNT(*) FROM source_string_cells", Integer.class));
    }

    private static void createHxs(Path path) throws Exception {
        HxsV1TestFixture.create(path, INSPECTION)
                .sheet(17, "Quest", 0, "en", 2, 2, hash(9), hash(10), hash(11), hash(12))
                .sheet(23, "Subrow", 1, "en", 1, 1, hash(19), hash(20), hash(21), hash(22))
                .column(17, 3, 16, 1)
                .column(17, 4, 24, 1)
                .column(23, 1, 8, 1)
                .row(17, 7, 0, hash(30), hash(31), hash(32), new byte[]{1})
                .row(17, 7, 1, hash(33), hash(34), hash(35), new byte[]{2})
                .row(23, 9, 0, hash(36), hash(37), hash(38), new byte[]{3})
                .stringCell(17, 7, 0, 3, "{utf8}Quest text", hash(42), hash(43), new byte[]{4})
                .stringCell(17, 7, 1, 4, "{utf8}Quest subrow", hash(44), null, new byte[]{5})
                .stringCell(23, 9, 0, 1, "{utf8}Subrow text", hash(45), hash(46), new byte[]{6});
    }

    private static void createLargeHxs(Path path, SourceSnapshotMetadata metadata) throws Exception {
        HxsV1TestFixture fixture = HxsV1TestFixture.create(path, metadata)
                .sheet(31, "Large", 0, "en", 1, metadata.rowCount(), hash(50), hash(51),
                        hash(52), hash(53))
                .column(31, 1, 8, 1);
        int count = Math.toIntExact(metadata.rowCount());
        for (int index = 0; index < count; index++) {
            fixture.row(31, index, 0, hash(index), hash(index + 1), hash(index + 2), new byte[]{1})
                    .stringCell(31, index, 0, 1, "{utf8}row-" + index, hash(index + 3),
                            null, new byte[]{2});
        }
    }

    private static void createSmallRowsLargeCellsHxs(Path path, SourceSnapshotMetadata metadata,
                                                      int rowCount, int cellsPerRow) throws Exception {
        HxsV1TestFixture fixture = HxsV1TestFixture.create(path, metadata)
                .sheet(71, "DenseStrings", 0, "en", cellsPerRow, rowCount,
                        hash(70), hash(71), hash(72), hash(73));
        for (int columnIndex = 1; columnIndex <= cellsPerRow; columnIndex++) {
            fixture.column(71, columnIndex, columnIndex * 8L, 1);
        }
        for (int rowId = 0; rowId < rowCount; rowId++) {
            fixture.row(71, rowId, 0, hash(rowId), hash(rowId + 1), hash(rowId + 2), new byte[]{1});
            for (int columnIndex = 1; columnIndex <= cellsPerRow; columnIndex++) {
                int seed = rowId * cellsPerRow + columnIndex;
                fixture.stringCell(71, rowId, 0, columnIndex,
                        "{utf8}r" + rowId + "-c" + columnIndex, hash(seed + 3), null,
                        new byte[]{2});
            }
        }
    }

    private static SourceSnapshotMetadata inspectionWithCounts(long sheetCount, long rowCount,
                                                        long stringCellCount) {
        return new SourceSnapshotMetadata(1, "7.2.0", "en", "full", SNAPSHOT_ID, CONTENT_ID,
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
