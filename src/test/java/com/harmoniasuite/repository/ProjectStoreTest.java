package com.harmoniasuite.repository;

import com.harmoniasuite.TestDatabases;
import com.harmoniasuite.domain.EntryIds;
import com.harmoniasuite.domain.PackAuthor;
import com.harmoniasuite.domain.PackMeta;
import com.harmoniasuite.domain.TranslationEntry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectStoreTest {

    @TempDir
    Path dir;

    private ProjectRepository projects;
    private EntryRepository entries;
    private PackRepository packs;
    private MergeRunRepository merges;
    private JdbcTemplate jdbc;
    private UUID projectId;

    private record EntryStats(long entries, long translated) {
    }

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
        e.setColumnName("Text");
        return e;
    }

    @BeforeEach
    void setUp() {
        jdbc = TestDatabases.sqlite(dir);
        projects = new ProjectRepository(jdbc);
        entries = new EntryRepository(jdbc);
        packs = new PackRepository(jdbc);
        merges = new MergeRunRepository(jdbc);
        String now = Instant.now().toString();
        projectId = projects.insert("pack-one", "rawexd/en", "dir", "out", "en", "ru", now);
        projects.upsertFiles(projectId, List.of("pack-one.csv"), now);
    }

    @Test
    @DisplayName("project round trip, DB generates v6 id")
    void projectRoundTrip() {
        ProjectRepository.ProjectRow row = projects.findById(projectId);
        assertEquals(projectId.toString(), projects.findByName("pack-one").id());
        assertEquals("rawexd/en", row.inputRoot());
        assertEquals("en", row.sourceLocale());
        assertNotNull(row.id());
        assertEquals('6', row.id().charAt(14));
        assertTrue(projects.existsByName("pack-one"));
    }

    @Test
    @DisplayName("no-translation is excluded from pending and counts as resolved")
    void noTranslationIsResolved() {
        String now = Instant.now().toString();
        Map<String, String> fileIds = projects.fileIdMap(projectId);
        entries.batchUpsert(projectId, fileIds, new ArrayList<>(List.of(
                entry("pack-one.csv", "10", 1, "Seize", "", "no_translation_required"))), now);
        assertEquals(0, entries.pendingByFile(projectId).size());
        assertEquals(0, entries.remainingCount(projectId));
        assertEquals(1, projects.summarize(projectId).translated());
    }

    @Test
    @DisplayName("empty project summary still counts source files")
    void emptyProjectSummaryCountsFiles() {
        ProjectRepository.ProjectSummary summary = projects.summarize(projectId);

        assertEquals(1L, summary.files());
        assertEquals(0L, summary.entries());
        assertEquals(0L, summary.translated());
        assertTrue(summary.byStatus().isEmpty());
    }

    @Test
    @DisplayName("pending rows group by file")
    void pendingByFile() {
        String now = Instant.now().toString();
        projects.upsertFiles(projectId, List.of("pack-two.csv"), now);
        Map<String, String> fileIds = projects.fileIdMap(projectId);
        entries.batchUpsert(projectId, fileIds, new ArrayList<>(List.of(
                entry("pack-one.csv", "10", 1, "Hello", "Привет", "human_reviewed"),
                entry("pack-one.csv", "11", 1, "Bye", "", "untranslated"),
                entry("pack-two.csv", "10", 1, "Morning", "", "untranslated"))), now);
        Map<String, Long> byFile = entries.pendingByFile(projectId);
        assertEquals(2, byFile.size());
        assertEquals(1L, byFile.get("pack-one.csv"));
        assertEquals(1L, byFile.get("pack-two.csv"));
    }

    @Test
    @DisplayName("entries: insert, filters and pagination")
    void entriesFilterAndPage() {
        String now = Instant.now().toString();
        Map<String, String> fileIds = projects.fileIdMap(projectId);
        entries.batchUpsert(projectId, fileIds, new ArrayList<>(List.of(
                entry("pack-one.csv", "10", 1, "Hello", "Привет", "human_reviewed"),
                entry("pack-one.csv", "11", 1, "Bye", "", "untranslated"))), now);
        assertEquals(2, entries.count(projectId, null));
        assertEquals(1, entries.count(projectId,
                new EntryRepository.EntryFilter(null, List.of(), null, null, true)));
        assertEquals(1, entries.count(projectId,
                new EntryRepository.EntryFilter(null, List.of(), null, "ell", false)));
        assertEquals(1, entries.count(projectId,
                new EntryRepository.EntryFilter("11", List.of(), null, null, false)));
        assertEquals(2, entries.count(projectId,
                new EntryRepository.EntryFilter(null, List.of(), "pack-one.csv", null, false)));
        assertEquals(1, entries.page(projectId, null, 0, 1).size());
        assertEquals(1, entries.translatedCount(projectId));
        assertEquals(1, entries.count(projectId,
                new EntryRepository.EntryFilter(null, List.of(), null, "hello", false)));
        assertEquals(1, entries.count(projectId,
                new EntryRepository.EntryFilter(null, List.of(), null, "bye", false)));
        assertEquals(1, entries.count(projectId,
                new EntryRepository.EntryFilter(null, List.of("untranslated"), null, null, false)));
        assertEquals(2, entries.count(projectId,
                new EntryRepository.EntryFilter(null, List.of("untranslated", "human_reviewed"), null, null, false)));
        TranslationEntry stored = entries.findByCell(projectId, EntryIds.ofCell("pack-one.csv", "10", 1));
        assertNotNull(stored.getUuid());
        assertNotNull(stored.getCreatedAt());
        assertNotNull(stored.getUpdatedAt());
        assertEquals(stored.getUuid(), entries.findById(UUID.fromString(stored.getUuid())).getUuid());
        assertEquals(stored.getUuid(), entries.findByCell(projectId, stored.getId()).getUuid());
    }

    @Test
    @DisplayName("re-extract keeps entry ids")
    void reupsertKeepsUuids() {
        String first = Instant.now().toString();
        Map<String, String> fileIds = projects.fileIdMap(projectId);
        entries.batchUpsert(projectId, fileIds,
                List.of(entry("pack-one.csv", "10", 1, "Hello", "Перевод", "human_reviewed")), first);
        String id = entries.findByCell(projectId, EntryIds.ofCell("pack-one.csv", "10", 1)).getUuid();
        String second = Instant.now().toString();
        entries.batchUpsert(projectId, fileIds,
                List.of(entry("pack-one.csv", "10", 1, "Hello", "Перевод", "human_reviewed")), second);
        entries.deleteStale(projectId, second);
        assertEquals(id, entries.findByCell(projectId, EntryIds.ofCell("pack-one.csv", "10", 1)).getUuid());
    }

    @Test
    @DisplayName("project summaries via live queries")
    void liveSummaries() {
        String now = Instant.now().toString();
        Map<String, String> fileIds = projects.fileIdMap(projectId);
        entries.batchUpsert(projectId, fileIds,
                List.of(entry("pack-one.csv", "10", 1, "Hello", "Привет", "human_reviewed")), now);
        var all = projects.summaries();
        assertEquals(1, all.size());
        var summary = projects.summarize(projectId);
        assertEquals(1L, summary.entries());
        assertEquals(1L, summary.translated());
        assertEquals(Map.of("human_reviewed", 1L), summary.byStatus());
        assertEquals(summary, all.get(projectId.toString()));
    }

    @Test
    @DisplayName("cell-mismatched id is rejected")
    void rejectsCellMismatch() {
        String now = Instant.now().toString();
        Map<String, String> fileIds = projects.fileIdMap(projectId);
        TranslationEntry bad = entry("pack-one.csv", "10", 1, "Hello", "", "untranslated");
        bad.setId("c_deadbeefdeadbeef");
        assertThrows(IllegalArgumentException.class,
                () -> entries.batchUpsert(projectId, fileIds, List.of(bad), now));
    }

    @Test
    @DisplayName("translation update and history")
    void updateAndHistory() {
        String now = Instant.now().toString();
        Map<String, String> fileIds = projects.fileIdMap(projectId);
        entries.batchUpsert(projectId, fileIds,
                List.of(entry("pack-one.csv", "10", 1, "Hello", "", "untranslated")), now);
        String cellId = EntryIds.ofCell("pack-one.csv", "10", 1);
        String id = entries.findByCell(projectId, cellId).getUuid();
        assertEquals(1, entries.updateTranslation(id, "Привет", "human_reviewed", now));
        assertEquals("Привет", entries.findById(UUID.fromString(id)).getTranslation());
        entries.insertHistory(projectId, id, "", "untranslated", "Привет", "human_reviewed", "editor", now);
        assertEquals(1L, projects.summarize(projectId).translated());
    }

    @Test
    @DisplayName("entry stats preserve translated semantics across all state changes")
    void entryStatsPreserveTranslatedSemantics() {
        String now = Instant.now().toString();
        Map<String, String> fileIds = projects.fileIdMap(projectId);
        entries.batchUpsert(projectId, fileIds, new ArrayList<>(List.of(
                entry("pack-one.csv", "10", 1, "A", "ok", "human_reviewed"),
                entry("pack-one.csv", "11", 1, "B", "", "untranslated"),
                entry("pack-one.csv", "12", 1, "C", "old", "stale"),
                entry("pack-one.csv", "13", 1, "D", "", "no_translation_required"))), now);

        assertEquals(2L, projects.summarize(projectId).translated());
        String id = entries.findByCell(projectId, EntryIds.ofCell("pack-one.csv", "11", 1)).getUuid();
        entries.updateTranslation(id, "new", "untranslated", now);
        assertEquals(3L, projects.summarize(projectId).translated());
        entries.updateTranslation(id, "new", "stale", now);
        assertEquals(2L, projects.summarize(projectId).translated());
        entries.updateTranslation(id, "new", "approved", now);
        assertEquals(3L, projects.summarize(projectId).translated());
        entries.updateTranslation(id, "", "no_translation_required", now);
        assertEquals(3L, projects.summarize(projectId).translated());
        entries.updateTranslation(id, "", "untranslated", now);
        assertEquals(2L, projects.summarize(projectId).translated());

        entries.deleteEntriesByFiles(projectId,
                List.of(UUID.fromString(fileIds.get("pack-one.csv"))));
        ProjectRepository.ProjectSummary summary = projects.summarize(projectId);
        assertEquals(0L, summary.entries());
        assertEquals(0L, summary.translated());
        assertTrue(summary.byStatus().isEmpty());
    }

    @Test
    @DisplayName("entry stats match direct aggregation after mutations")
    void entryStatsMatchDirectAggregation() {
        String now = Instant.now().toString();
        Map<String, String> fileIds = projects.fileIdMap(projectId);
        entries.batchUpsert(projectId, fileIds, new ArrayList<>(List.of(
                entry("pack-one.csv", "10", 1, "A", "", "untranslated"),
                entry("pack-one.csv", "11", 1, "B", "old", "stale"),
                entry("pack-one.csv", "12", 1, "C", "", "no_translation_required"),
                entry("pack-one.csv", "13", 1, "D", "done", "approved"),
                entry("pack-one.csv", "14", 1, "E", " future ", "future_status"),
                entry("pack-one.csv", "15", 1, "F", "", "future_status"))), now);
        assertEntryStatsMatchEntries();

        String mutableId = entries.findByCell(
                projectId, EntryIds.ofCell("pack-one.csv", "10", 1)).getUuid();
        String staleId = entries.findByCell(
                projectId, EntryIds.ofCell("pack-one.csv", "11", 1)).getUuid();

        jdbc.update("UPDATE entries SET translation = ? WHERE id = ?",
                " fresh ", UUID.fromString(mutableId));
        assertEntryStatsMatchEntries();
        entries.updateTranslation(mutableId, "fresh", "stale", now);
        assertEntryStatsMatchEntries();
        entries.updateTranslation(mutableId, "fresh", "approved", now);
        assertEntryStatsMatchEntries();
        entries.updateTranslation(mutableId, "", "no_translation_required", now);
        assertEntryStatsMatchEntries();
        entries.updateTranslation(mutableId, "", "untranslated", now);
        assertEntryStatsMatchEntries();

        jdbc.update("DELETE FROM entries WHERE id = ?", UUID.fromString(staleId));
        assertEntryStatsMatchEntries();
        assertEquals(0L, statBucketCount("stale"));
        entries.deleteEntriesByFiles(projectId,
                List.of(UUID.fromString(fileIds.get("pack-one.csv"))));
        assertEntryStatsMatchEntries();
        assertEquals(0L, statBucketCount("untranslated"));
    }

    private void assertEntryStatsMatchEntries() {
        Map<String, EntryStats> expected = stats(jdbc.queryForList("""
                SELECT status, COUNT(*) AS entries_count,
                       COALESCE(SUM(CASE
                           WHEN status = 'no_translation_required' THEN 1
                           WHEN status <> 'stale' AND NULLIF(TRIM(translation), '') IS NOT NULL THEN 1
                           ELSE 0
                       END), 0) AS translated_count
                FROM entries
                WHERE project_id = ?
                GROUP BY status""", projectId));
        Map<String, EntryStats> actual = stats(jdbc.queryForList("""
                SELECT status, entries_count, translated_count
                FROM entry_stats
                WHERE project_id = ?""", projectId));
        assertEquals(expected, actual);
    }

    private static Map<String, EntryStats> stats(List<Map<String, Object>> rows) {
        Map<String, EntryStats> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            result.put((String) row.get("status"), new EntryStats(
                    ((Number) row.get("entries_count")).longValue(),
                    ((Number) row.get("translated_count")).longValue()));
        }
        return result;
    }

    @Test
    @DisplayName("entry stats cleanup only removes the affected status bucket")
    void entryStatsCleanupOnlyRemovesAffectedBucket() {
        String now = Instant.now().toString();
        Map<String, String> fileIds = projects.fileIdMap(projectId);
        entries.batchUpsert(projectId, fileIds,
                List.of(entry("pack-one.csv", "10", 1, "A", "", "untranslated")), now);
        jdbc.update("""
                INSERT INTO entry_stats (project_id, status, entries_count, translated_count)
                VALUES (?, ?, 0, 0)""", projectId, "unrelated");

        String id = entries.findByCell(
                projectId, EntryIds.ofCell("pack-one.csv", "10", 1)).getUuid();
        entries.updateTranslation(id, "done", "approved", now);
        assertEquals(1L, statBucketCount("unrelated"));

        jdbc.update("""
                INSERT INTO entry_stats (project_id, status, entries_count, translated_count)
                VALUES (?, ?, 0, 0)""", projectId, "unrelated_after_update");
        entries.deleteEntriesByFiles(projectId,
                List.of(UUID.fromString(fileIds.get("pack-one.csv"))));
        assertEquals(1L, statBucketCount("unrelated_after_update"));
    }

    @Test
    @DisplayName("entry stats constraints reject invalid counters")
    void entryStatsConstraintsRejectInvalidCounters() {
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO entry_stats (project_id, status, entries_count, translated_count)
                VALUES (?, ?, -1, 0)""", projectId, "negative_entries"));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO entry_stats (project_id, status, entries_count, translated_count)
                VALUES (?, ?, 1, -1)""", projectId, "negative_translated"));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO entry_stats (project_id, status, entries_count, translated_count)
                VALUES (?, ?, 1, 2)""", projectId, "too_many_translated"));
    }

    private long statBucketCount(String status) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM entry_stats
                WHERE project_id = ? AND status = ?""", Long.class, projectId, status);
    }

    @Test
    @DisplayName("project cascade removes entries and stats without trigger conflicts")
    void projectCascadeRemovesEntriesAndStats() {
        String now = Instant.now().toString();
        Map<String, String> fileIds = projects.fileIdMap(projectId);
        entries.batchUpsert(projectId, fileIds, new ArrayList<>(List.of(
                entry("pack-one.csv", "10", 1, "A", "", "untranslated"),
                entry("pack-one.csv", "11", 1, "B", "done", "approved"))), now);
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM entries WHERE project_id = ?", Long.class, projectId));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM entry_stats WHERE project_id = ?", Long.class, projectId));

        assertEquals(1, projects.delete(projectId));

        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM entries WHERE project_id = ?", Long.class, projectId));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM entry_stats WHERE project_id = ?", Long.class, projectId));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_files WHERE project_id = ?", Long.class, projectId));
        assertFalse(projects.exists(projectId));
    }

    @Test
    @DisplayName("per-file progress in one query")
    void progressByFile() {
        String now = Instant.now().toString();
        Map<String, String> fileIds = projects.fileIdMap(projectId);
        entries.batchUpsert(projectId, fileIds, new ArrayList<>(List.of(
                entry("pack-one.csv", "10", 1, "Hello", "Привет", "human_reviewed"),
                entry("pack-one.csv", "11", 1, "Bye", "", "untranslated"))), now);
        var rows = entries.progressByFile(projectId);
        assertEquals(1, rows.size());
        assertEquals("pack-one.csv", rows.get(0).get("file"));
        assertEquals(2L, ((Number) rows.get(0).get("total")).longValue());
        assertEquals(1L, ((Number) rows.get(0).get("done")).longValue());
    }

    @Test
    @DisplayName("pack round trip")
    void packRoundTrip() {
        String now = Instant.now().toString();
        PackMeta pack = new PackMeta();
        pack.setPackId("pack-one");
        pack.setGameVersion("2026.08.11.0000.0000");
        PackAuthor author = new PackAuthor();
        author.setName("Author One");
        pack.setAuthors(new ArrayList<>(List.of(author)));
        pack.setLanguages(new ArrayList<>(List.of("ru")));
        pack.setCompatibleGameVersions(new ArrayList<>(List.of("2026.08.11.0000.0000")));
        packs.save(projectId, pack, now);
        PackMeta loaded = packs.load(projectId);
        assertEquals("pack-one", loaded.getPackId());
        assertEquals(1, loaded.getAuthors().size());
        assertEquals(List.of("ru"), loaded.getLanguages());
    }

    @Test
    @DisplayName("merge runs: start and finish")
    void mergeRunLifecycle() {
        String runId = merges.start(projectId, Instant.now().toString(), "in", "out", 3);
        assertNotNull(runId);
        merges.finish(runId, Instant.now().toString(), "completed", 3, 10, "[]");
        assertEquals("completed", merges.last(projectId).get("status"));
    }
}
