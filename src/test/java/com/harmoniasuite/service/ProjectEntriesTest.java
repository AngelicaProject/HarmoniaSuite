package com.harmoniasuite.service;

import com.harmoniasuite.TestDatabases;
import com.harmoniasuite.config.HarmoniaProperties;
import com.harmoniasuite.config.WorkspacePaths;
import com.harmoniasuite.domain.EntryIds;
import com.harmoniasuite.domain.TranslationEntry;
import com.harmoniasuite.dto.EntriesPageDto;
import com.harmoniasuite.dto.EntryDto;
import com.harmoniasuite.dto.OverviewDto;
import com.harmoniasuite.dto.ProjectFilesDto;
import com.harmoniasuite.exception.HarmoniaSuiteNotFoundException;
import com.harmoniasuite.mapping.EntryMapperImpl;
import com.harmoniasuite.mapping.ProjectMapperImpl;
import com.harmoniasuite.repository.EntryRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectEntriesTest {

    @TempDir
    Path workspace;

    @TempDir
    Path dbDir;

    private ProjectService projects;
    private ProjectRepository projectRepository;
    private EntryRepository entryRepository;
    private UUID projectId;

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

    @BeforeEach
    void setUp() throws Exception {
        HarmoniaProperties properties = new HarmoniaProperties();
        properties.setWorkspace(workspace.toString());
        JdbcTemplate jdbc = TestDatabases.sqlite(dbDir);
        projectRepository = new ProjectRepository(jdbc);
        entryRepository = new EntryRepository(jdbc);
        String now = Instant.now().toString();
        projectId = projectRepository.insert(
                "t", "rawexd/en", "dir", "out", "en", "ru", now);
        projectRepository.upsertFiles(projectId, List.of("a.csv"), now);
        entryRepository.batchUpsert(projectId, projectRepository.fileIdMap(projectId),
                new ArrayList<>(List.of(
                        entry("a.csv", "10", 1, "Hello", "Привет", "needs_human_review"),
                        entry("a.csv", "11", 1, "Bye", "", "untranslated"))), now);
        projects = new ProjectService(new WorkspacePaths(properties), projectRepository, entryRepository,
                new EntryMapperImpl(), new ProjectMapperImpl());
    }

    private static EntryRepository.EntryFilter all() {
        return new EntryRepository.EntryFilter(null, List.of(), null, null, false);
    }

    @Test
    @DisplayName("entries without filters returns everything")
    void entriesWithoutFiltersReturnsEverything() throws Exception {
        EntriesPageDto payload = projects.entries(projectId, all(), 0, 0);
        assertEquals(2L, payload.total());
        assertEquals(2, payload.entries().size());
    }

    @Test
    @DisplayName("unknown project UUID is rejected")
    void unknownProjectIdRejected() throws Exception {
        assertThrows(HarmoniaSuiteNotFoundException.class, () ->
                projects.entries(UUID.randomUUID(), all(), 0, 0));
        assertThrows(HarmoniaSuiteNotFoundException.class, () ->
                projects.overview(UUID.randomUUID()));
    }

    @Test
    @DisplayName("untranslated filter")
    void entriesFiltersUntranslated() throws Exception {
        EntryRepository.EntryFilter filter =
                new EntryRepository.EntryFilter(null, List.of(), null, null, true);
        assertEquals(1L, projects.entries(projectId, filter, 0, 0).total());
    }

    @Test
    @DisplayName("q search covers source and translation, rowKey is exact")
    void entriesSearchesByQueryAndRowKey() throws Exception {
        EntryRepository.EntryFilter byQuery =
                new EntryRepository.EntryFilter(null, List.of(), null, "ell", false);
        assertEquals(1L, projects.entries(projectId, byQuery, 0, 0).total());
        EntryRepository.EntryFilter byTranslation =
                new EntryRepository.EntryFilter(null, List.of(), null, "прив", false);
        assertEquals(1L, projects.entries(projectId, byTranslation, 0, 0).total());
        EntryRepository.EntryFilter byRow =
                new EntryRepository.EntryFilter("11", List.of(), null, null, false);
        assertEquals(1L, projects.entries(projectId, byRow, 0, 0).total());
        EntryRepository.EntryFilter byFile =
                new EntryRepository.EntryFilter(null, List.of(), "a.csv", null, false);
        assertEquals(2L, projects.entries(projectId, byFile, 0, 0).total());
        assertEquals(0L, projects.entries(projectId,
                new EntryRepository.EntryFilter(null, List.of(), null, "zzz-заглушка", false),
                0, 0).total());
    }

    @Test
    @DisplayName("status filter is exact, unknown status is rejected")
    void entriesFiltersByExactStatus() throws Exception {
        assertEquals(1L, projects.entries(projectId,
                new EntryRepository.EntryFilter(null, List.of("untranslated"), null, null, false),
                0, 0).total());
        assertEquals(2L, projects.entries(projectId,
                new EntryRepository.EntryFilter(
                        null, List.of("untranslated", "needs_human_review"), null, null, false),
                0, 0).total());
        assertThrows(IllegalArgumentException.class, () ->
                ProjectService.normalizeStatuses("untranslated, bogus-status"));
    }

    @Test
    @DisplayName("pagination is always capped")
    void entriesPaginationIsBounded() throws Exception {
        EntriesPageDto page = projects.entries(projectId, all(), 0, 1);
        assertEquals(2L, page.total());
        assertEquals(1, page.entries().size());
        assertEquals(0, page.offset());
        assertEquals(1, page.limit());
        EntriesPageDto def = projects.entries(projectId, all(), -5, 0);
        assertEquals(ProjectService.ENTRIES_DEFAULT_LIMIT, def.limit());
        assertEquals(0, def.offset());
        EntriesPageDto capped = projects.entries(projectId, all(), 0, Integer.MAX_VALUE);
        assertEquals(ProjectService.ENTRIES_MAX_LIMIT, capped.limit());
    }

    @Test
    @DisplayName("overview returns id, name, summary and selection")
    void overviewReturnsIdNameSummaryAndSelection() throws Exception {
        OverviewDto overview = projects.overview(projectId);
        assertEquals(projectId.toString(), overview.id());
        assertEquals("t", overview.name());
        assertEquals("en", overview.sourceLocale());
        assertEquals("ru", overview.targetLocale());
        assertEquals(2L, overview.summary().entries());
        assertEquals(1L, overview.summary().translated());
    }

    @Test
    @DisplayName("files search, pagination and hiding ready files")
    void filesSearchPaginateAndHideReady() throws Exception {
        String now = Instant.now().toString();
        projectRepository.upsertFiles(projectId, List.of("b.csv", "c.csv"), now);
        entryRepository.batchUpsert(projectId, projectRepository.fileIdMap(projectId),
                new ArrayList<>(List.of(
                        entry("b.csv", "1", 1, "Hi", "Привет", "approved"),
                        entry("c.csv", "1", 1, "Yo", "", "untranslated"))), now);
        ProjectFilesDto all = projects.listFiles(projectId, null, false, 0, 10);
        assertEquals(3L, all.total());
        assertEquals(2L, all.needFiles());
        assertEquals(1L, all.readyFiles());
        assertEquals(List.of("a.csv", "c.csv", "b.csv"),
                all.files().stream().map(f -> f.path()).toList());
        ProjectFilesDto page = projects.listFiles(projectId, null, false, 1, 1);
        assertEquals(3L, page.total());
        assertEquals(1, page.files().size());
        assertEquals("c.csv", page.files().get(0).path());
        assertEquals(1, page.offset());
        assertEquals(1, page.limit());
        ProjectFilesDto def = projects.listFiles(projectId, null, false, 0, 0);
        assertEquals(ProjectService.FILES_DEFAULT_LIMIT, def.limit());
        ProjectFilesDto found = projects.listFiles(projectId, "b.c", false, 0, 10);
        assertEquals(1L, found.total());
        assertEquals("b.csv", found.files().get(0).path());
        ProjectFilesDto need = projects.listFiles(projectId, null, true, 0, 10);
        assertEquals(2L, need.total());
        assertEquals(2L, need.needFiles());
        assertEquals(0L, need.readyFiles());
    }

    @Test
    @DisplayName("empty files are neither ready nor needy")
    void emptyFilesAreNeitherReadyNorNeed() throws Exception {
        String now = Instant.now().toString();
        projectRepository.upsertFiles(projectId, List.of("empty.csv"), now);
        ProjectFilesDto all = projects.listFiles(projectId, null, false, 0, 10);
        assertEquals(2L, all.total());
        assertEquals(1L, all.needFiles());
        assertEquals(0L, all.readyFiles());
        var empty = all.files().stream().filter(f -> f.path().equals("empty.csv")).findFirst();
        assertTrue(empty.isPresent());
        assertEquals(0L, empty.get().total());
    }

    @Test
    @DisplayName("tree returns all files with live counts")
    void fileTreeReturnsAllWithLiveCounts() throws Exception {
        String now = Instant.now().toString();
        projectRepository.upsertFiles(projectId, List.of("sub/empty.csv"), now);
        var tree = projects.fileTree(projectId);
        assertEquals(2L, tree.total());
        assertEquals(List.of("a.csv", "sub/empty.csv"),
                tree.files().stream().map(f -> f.path()).toList());
        var empty = tree.files().stream().filter(f -> f.path().equals("sub/empty.csv")).findFirst();
        assertTrue(empty.isPresent());
        assertEquals(0L, empty.get().total());
    }

    @Test
    @DisplayName("files with readyOnly returns only translated files")
    void filesReadyOnlyReturnsTranslatedFilesOnly() {
        ProjectFilesDto ready = projects.listFiles(projectId, null, false, true, 0, 10);
        assertEquals(1L, ready.total());
        assertEquals(1, ready.files().size());
        assertEquals("a.csv", ready.files().get(0).path());
    }

    @Test
    @DisplayName("files returns per-file stats and summary")
    void filesReturnsPerFileStats() throws Exception {
        ProjectFilesDto files = projects.listFiles(projectId, null, false, 0, 0);
        assertEquals(1, files.files().size());
        assertEquals("a.csv", files.files().get(0).path());
        assertEquals(2L, files.files().get(0).total());
        assertEquals(1L, files.files().get(0).translated());
        assertEquals(1L, files.files().get(0).untranslated());
        assertEquals(2L, files.summary().entries());
    }

    @Test
    @DisplayName("single entry found by cell id and by uuid")
    void singleEntryByCellAndUuid() throws Exception {
        EntryDto byCell = projects.entryByCell(projectId, EntryIds.ofCell("a.csv", "10", 1));
        assertEquals("Hello", byCell.source());
        EntryDto byUuid = projects.entry(projectId, UUID.fromString(byCell.uuid()));
        assertEquals(byCell.id(), byUuid.id());
    }

    @Test
    @DisplayName("unknown entry is rejected")
    void unknownEntryRejected() throws Exception {
        assertThrows(HarmoniaSuiteNotFoundException.class, () ->
                projects.entryByCell(projectId, "c_0000000000000000"));
        assertThrows(HarmoniaSuiteNotFoundException.class, () ->
                projects.updateEntry(projectId, UUID.randomUUID(), "Перевод", null));
    }

    @Test
    @DisplayName("foreign project entry is out of reach")
    void entryScopedToProject() throws Exception {
        UUID other = projectRepository.insert(
                "other", "rawexd/en", "dir", "out", "en", "ru", Instant.now().toString());
        UUID entryId = entryUuid("a.csv", "10", 1);
        assertThrows(HarmoniaSuiteNotFoundException.class, () -> projects.entry(other, entryId));
        assertThrows(HarmoniaSuiteNotFoundException.class, () ->
                projects.updateEntry(other, entryId, "Перевод", null));
    }

    @Test
    @DisplayName("duplicate-name create rejected, delete by id")
    void createDuplicateNameRejectedDeleteById() throws Exception {
        assertThrows(IllegalArgumentException.class, () ->
                projects.createProject("t", "rawexd/en"));
        List<String> log = new ArrayList<>();
        projects.deleteProject(projectId, log::add);
        assertTrue(log.stream().anyMatch(l -> l.contains("Записей к удалению: 2")));
        assertThrows(HarmoniaSuiteNotFoundException.class, () -> projects.overview(projectId));
    }

    @Test
    @DisplayName("delete reports per batch and cleans everything")
    void deleteReportsBatchesAndCleansAll() throws Exception {
        List<String> log = new ArrayList<>();
        projects.deleteProject(projectId, log::add);
        assertEquals(0L, entryRepository.count(projectId, null));
        assertTrue(log.stream().anyMatch(l -> l.contains("Записей к удалению: 2")));
        assertTrue(log.stream().anyMatch(l -> l.contains("Удалено записей: 2 / 2")));
        assertThrows(HarmoniaSuiteNotFoundException.class, () -> projects.overview(projectId));
    }

    @Test
    @DisplayName("cancelled delete keeps the project and repairs counts")
    void deleteCancelledKeepsProject() throws Exception {
        Thread.currentThread().interrupt();
        try {
            List<String> log = new ArrayList<>();
            assertThrows(InterruptedException.class, () -> projects.deleteProject(projectId, log::add));
            assertTrue(log.stream().anyMatch(l -> l.contains("Отменено")));
        } finally {
            Thread.interrupted();
        }
        assertEquals(2L, entryRepository.count(projectId, null));
        assertEquals(2L, projects.overview(projectId).summary().entries());
    }

    private UUID entryUuid(String file, String rowKey, int col) {
        return UUID.fromString(
                entryRepository.findByCell(projectId, EntryIds.ofCell(file, rowKey, col)).getUuid());
    }

    @Test
    @DisplayName("saving a translation updates the summary")
    void updateEntryPersistsTranslation() throws Exception {
        var res = projects.updateEntry(projectId, entryUuid("a.csv", "11", 1), "Пока", null);
        assertTrue(res.ok());
        assertEquals("needs_human_review", res.entry().status());
        assertEquals(2L, res.summary().translated());
        assertEquals("Пока", projects.entryByCell(projectId, EntryIds.ofCell("a.csv", "11", 1)).translation());
    }

    @Test
    @DisplayName("saving a translation updates file counters without re-extract")
    void updateEntryRefreshesFileCounters() throws Exception {
        ProjectFilesDto before = projects.listFiles(projectId, null, false, 0, 0);
        assertEquals(1L, before.files().get(0).translated());
        projects.updateEntry(projectId, entryUuid("a.csv", "11", 1), "Пока", null);
        ProjectFilesDto after = projects.listFiles(projectId, null, false, 0, 0);
        assertEquals(2L, after.files().get(0).total());
        assertEquals(2L, after.files().get(0).translated());
        assertEquals(0L, after.files().get(0).untranslated());
        assertEquals(1L, after.readyFiles());
    }

    @Test
    @DisplayName("saving with an unknown status is rejected")
    void updateEntryRejectsUnknownStatus() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> projects.updateEntry(
                projectId, entryUuid("a.csv", "11", 1), "Пока", "bogus-status"));
    }
}
