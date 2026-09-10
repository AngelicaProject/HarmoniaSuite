package com.harmoniasuite.service.project;

import com.harmoniasuite.TestDatabases;
import com.harmoniasuite.config.HarmoniaProperties;
import com.harmoniasuite.config.WorkspacePaths;
import com.harmoniasuite.domain.EntryIds;
import com.harmoniasuite.domain.TranslationEntry;
import com.harmoniasuite.dto.DeltaExportDto;
import com.harmoniasuite.dto.DeltaImportRequest;
import com.harmoniasuite.dto.DeltaImportResultDto;
import com.harmoniasuite.dto.DeltaRowDto;
import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import com.harmoniasuite.mapping.EntryMapperImpl;
import com.harmoniasuite.mapping.ProjectMapperImpl;
import com.harmoniasuite.repository.EntryRepository;
import com.harmoniasuite.repository.PackRepository;
import com.harmoniasuite.repository.ProjectRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeltaServiceTest {

    @TempDir
    Path workspace;

    @TempDir
    Path dbDir;

    private DeltaService delta;
    private ProjectRepository projectRepository;
    private EntryRepository entryRepository;
    private UUID projectId;
    private String now;

    private static TranslationEntry entry(String file, String rowKey, int col, String source,
            String translation, String status) {
        TranslationEntry e = new TranslationEntry();
        e.setId(EntryIds.ofCell(file, rowKey, col));
        e.setSource(source);
        e.setTranslation(translation);
        e.setStatus(status);
        e.setFile(file);
        e.setRowKey(rowKey);
        e.setColumnIndex(col);
        return e;
    }

    private static DeltaRowDto row(String cellId, String file, String source, String translation, String status) {
        return new DeltaRowDto(cellId, file, source, translation, status);
    }

    private DeltaImportRequest request(List<DeltaRowDto> rows) {
        return new DeltaImportRequest("Author One",
                null, null, "fp-one", null, rows);
    }

    @BeforeEach
    void setUp() throws Exception {
        HarmoniaProperties properties = new HarmoniaProperties();
        properties.setWorkspace(workspace.toString());
        JdbcTemplate jdbc = TestDatabases.sqlite(dbDir);
        projectRepository = new ProjectRepository(jdbc);
        entryRepository = new EntryRepository(jdbc);
        PackRepository packRepository = new PackRepository(jdbc);
        now = Instant.now().toString();
        projectId = projectRepository.insert(
                "t", "rawexd/en", "dir", "out", "en", "ru", now);
        projectRepository.updateSourcesFingerprint(projectId, "fp-one", now);
        projectRepository.upsertFiles(projectId, List.of("a.csv", "b.csv"), now);
        entryRepository.batchUpsert(projectId, projectRepository.fileIdMap(projectId),
                new ArrayList<>(List.of(
                        entry("a.csv", "10", 1, "Hello", "", "untranslated"),
                        entry("a.csv", "11", 1, "Bye", "", "untranslated"),
                        entry("b.csv", "10", 1, "Morning", "", "untranslated"))), now);
        delta = new DeltaService(new WorkspacePaths(properties), projectRepository, entryRepository,
                packRepository, new ProjectMapperImpl(), new EntryMapperImpl(),
                new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    private void translateAll() {
        delta.importDelta(projectId, request(List.of(
                row(EntryIds.ofCell("a.csv", "10", 1), "a.csv", "Hello", "Привет", "human_reviewed"),
                row(EntryIds.ofCell("a.csv", "11", 1), "a.csv", "Bye", "Пока", "human_reviewed"),
                row(EntryIds.ofCell("b.csv", "10", 1), "b.csv", "Morning", "Утро", "human_reviewed"))));
    }

    @Test
    @DisplayName("export skips untouched rows")
    void exportSkipsUntouchedRows() {
        DeltaExportDto page = delta.exportDelta(projectId, "", "", "", 100, "Author One");
        assertTrue(page.rows().isEmpty());
        assertTrue(page.complete());
    }

    @Test
    @DisplayName("export without an author is rejected")
    void exportWithoutAuthorIsRejected() {
        assertThrows(HarmoniaSuiteBadRequestException.class, () ->
                delta.exportDelta(projectId, "", "", "", 100, null));
        assertThrows(HarmoniaSuiteBadRequestException.class, () ->
                delta.exportDelta(projectId, "", "", "", 100, "  "));
    }

    @Test
    @DisplayName("export walks the tuple and collects all rows")
    void exportWalksTuplePagesToComplete() {
        translateAll();
        List<String> seen = new ArrayList<>();
        String marker = "";
        String cell = "";
        boolean complete = false;
        int pages = 0;
        while (!complete && pages < 10) {
            DeltaExportDto page = delta.exportDelta(projectId, marker, cell, "", 1, "Author One");
            page.rows().forEach(r -> seen.add(r.cellId()));
            marker = page.nextSinceUpdatedAt();
            cell = page.nextSinceCellId();
            complete = page.complete();
            pages++;
        }
        assertTrue(complete);
        assertEquals(3, seen.size());
        assertEquals(3, seen.stream().distinct().count());
        assertEquals("fp-one", delta.exportDelta(projectId, "", "", "", 100, "Author One").header().sourcesFp());
    }

    @Test
    @DisplayName("export with a file filter keeps only its own files")
    void exportWithFilesFilterKeepsOwnFiles() {
        translateAll();
        DeltaExportDto page = delta.exportDelta(projectId, "", "", "b.csv", 100, "Author One");
        assertEquals(1, page.rows().size());
        assertEquals("b.csv", page.rows().get(0).filePath());
    }

    @Test
    @DisplayName("preview counts but writes nothing")
    void previewCountsWithoutWriting() {
        String cell = EntryIds.ofCell("a.csv", "10", 1);
        DeltaImportResultDto result = delta.preview(projectId,
                request(List.of(row(cell, "a.csv", "Hello", "Привет", "human_reviewed"))));
        assertEquals(1, result.applied());
        assertEquals("", entryRepository.findByCells(projectId, List.of(cell)).get(cell).getTranslation());
        assertEquals(0, projectRepository.summarize(projectId).translated());
    }

    @Test
    @DisplayName("import applies clean rows and reconciles stats")
    void importAppliesCleanRows() {
        String cell = EntryIds.ofCell("a.csv", "10", 1);
        DeltaImportResultDto result = delta.importDelta(projectId,
                request(List.of(row(cell, "a.csv", "Hello", "Привет", "human_reviewed"))));
        assertEquals(1, result.applied());
        assertEquals(0, result.noop());
        assertTrue(result.skipped().isEmpty());
        assertTrue(result.conflicts().isEmpty());
        assertEquals("Привет",
                entryRepository.findByCells(projectId, List.of(cell)).get(cell).getTranslation());
        assertEquals(1, projectRepository.summarize(projectId).translated());
        assertEquals(1, result.summary().translated());
    }

    @Test
    @DisplayName("reimporting the same delta is a noop")
    void reimportSameDeltaGivesNoop() {
        String cell = EntryIds.ofCell("a.csv", "10", 1);
        DeltaImportRequest req = request(List.of(row(cell, "a.csv", "Hello", "Привет", "human_reviewed")));
        delta.importDelta(projectId, req);
        DeltaImportResultDto again = delta.importDelta(projectId, req);
        assertEquals(0, again.applied());
        assertEquals(1, again.noop());
    }

    @Test
    @DisplayName("foreign fingerprint rejects the whole request")
    void foreignFingerprintRejectsWholeRequest() {
        String cell = EntryIds.ofCell("a.csv", "10", 1);
        DeltaImportRequest req = new DeltaImportRequest("Author One",
                null, null, "fp-other", null,
                List.of(row(cell, "a.csv", "Hello", "Привет", "human_reviewed")));
        assertThrows(HarmoniaSuiteBadRequestException.class, () -> delta.importDelta(projectId, req));
        assertEquals("", entryRepository.findByCells(projectId, List.of(cell)).get(cell).getTranslation());
    }

    @Test
    @DisplayName("mismatched source goes to skipped without writing")
    void sourceMismatchGoesToSkipped() {
        String cell = EntryIds.ofCell("a.csv", "10", 1);
        DeltaImportResultDto result = delta.importDelta(projectId,
                request(List.of(row(cell, "a.csv", "Hello?", "Привет", "human_reviewed"))));
        assertEquals(0, result.applied());
        assertEquals(1, result.skipped().size());
        assertEquals("source_mismatch", result.skipped().get(0).reason());
        assertEquals("", entryRepository.findByCells(projectId, List.of(cell)).get(cell).getTranslation());
    }

    @Test
    @DisplayName("re-editing a human cell gives a conflict without overwrite")
    void humanOverwriteGivesConflictWithoutOverwrite() {
        String cell = EntryIds.ofCell("a.csv", "10", 1);
        delta.importDelta(projectId,
                request(List.of(row(cell, "a.csv", "Hello", "Привет", "human_reviewed"))));
        DeltaImportResultDto result = delta.importDelta(projectId,
                request(List.of(row(cell, "a.csv", "Hello", "Пока", "human_reviewed"))));
        assertEquals(0, result.applied());
        assertEquals(1, result.conflicts().size());
        assertEquals(cell, result.conflicts().get(0).cellId());
        assertEquals("Привет",
                entryRepository.findByCells(projectId, List.of(cell)).get(cell).getTranslation());
    }

    @Test
    @DisplayName("allowlist cuts foreign files")
    void allowlistCutsForeignFiles() {
        String cell = EntryIds.ofCell("b.csv", "10", 1);
        DeltaImportRequest req = new DeltaImportRequest("Author One",
                List.of("a.csv"), null, "fp-one", null,
                List.of(row(cell, "b.csv", "Morning", "Утро", "human_reviewed")));
        DeltaImportResultDto result = delta.importDelta(projectId, req);
        assertEquals(0, result.applied());
        assertEquals("not_assigned", result.skipped().get(0).reason());
    }

    @Test
    @DisplayName("over-cap and unknown statuses go to skipped")
    void statusCapAndUnknownStatusGoToSkipped() {
        String approved = EntryIds.ofCell("a.csv", "10", 1);
        String unknown = EntryIds.ofCell("a.csv", "11", 1);
        DeltaImportResultDto result = delta.importDelta(projectId, request(List.of(
                row(approved, "a.csv", "Hello", "Привет", "approved"),
                row(unknown, "a.csv", "Bye", "Пока", "almost_done"))));
        assertEquals(0, result.applied());
        assertEquals(2, result.skipped().size());
        assertEquals("status_above_cap", result.skipped().get(0).reason());
        assertEquals("bad_status", result.skipped().get(1).reason());
    }
}
