package com.harmoniasuite.repository;

import com.harmoniasuite.TestDatabases;
import com.harmoniasuite.domain.EntryIds;
import com.harmoniasuite.domain.PackAuthor;
import com.harmoniasuite.domain.PackMeta;
import com.harmoniasuite.domain.TranslationEntry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        e.setColumnName("Text");
        return e;
    }

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = TestDatabases.sqlite(dir);
        projects = new ProjectRepository(jdbc);
        entries = new EntryRepository(jdbc);
        packs = new PackRepository(jdbc);
        merges = new MergeRunRepository(jdbc);
        String now = Instant.now().toString();
        projectId = projects.insert("pack-one", "rawexd/en", "dir", "out", "en", "ru", now);
        projects.upsertFiles(projectId, List.of("pack-one.csv"), now);
    }

    @Test
    @DisplayName("проект: создание и чтение, id генерит БД в формате v6")
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
    @DisplayName("без перевода исключается из pending и считается разобранным")
    void noTranslationIsResolved() {
        String now = Instant.now().toString();
        Map<String, String> fileIds = projects.fileIdMap(projectId);
        entries.batchUpsert(projectId, fileIds, new ArrayList<>(List.of(
                entry("pack-one.csv", "10", 1, "Seize", "", "no_translation_required"))), now);
        assertEquals(0, entries.pendingByFile(projectId).size());
        assertEquals(0, entries.remaining(projectId));
        assertEquals(1, projects.summarize(projectId).translated());
    }

    @Test
    @DisplayName("строки к переводу группируются по файлам")
    void pendingByFile() {
        String now = Instant.now().toString();
        projects.upsertFiles(projectId, List.of("pack-two.csv"), now);
        Map<String, String> fileIds = projects.fileIdMap(projectId);
        entries.batchUpsert(projectId, fileIds, new ArrayList<>(List.of(
                entry("pack-one.csv", "10", 1, "Hello", "Привет", "needs_human_review"),
                entry("pack-one.csv", "11", 1, "Bye", "", "untranslated"),
                entry("pack-two.csv", "10", 1, "Morning", "", "untranslated"))), now);
        Map<String, Long> byFile = entries.pendingByFile(projectId);
        assertEquals(2, byFile.size());
        assertEquals(1L, byFile.get("pack-one.csv"));
        assertEquals(1L, byFile.get("pack-two.csv"));
    }

    @Test
    @DisplayName("записи: вставка, фильтры и пагинация")
    void entriesFilterAndPage() {
        String now = Instant.now().toString();
        Map<String, String> fileIds = projects.fileIdMap(projectId);
        entries.batchUpsert(projectId, fileIds, new ArrayList<>(List.of(
                entry("pack-one.csv", "10", 1, "Hello", "Привет", "needs_human_review"),
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
                new EntryRepository.EntryFilter(null, List.of("untranslated", "needs_human_review"), null, null, false)));
        TranslationEntry stored = entries.findByCell(projectId, EntryIds.ofCell("pack-one.csv", "10", 1));
        assertNotNull(stored.getUuid());
        assertNotNull(stored.getCreatedAt());
        assertNotNull(stored.getUpdatedAt());
        assertEquals(stored.getUuid(), entries.findById(UUID.fromString(stored.getUuid())).getUuid());
        assertEquals(stored.getUuid(), entries.findByCell(projectId, stored.getId()).getUuid());
    }

    @Test
    @DisplayName("повторный extract сохраняет id записей")
    void reupsertKeepsUuids() {
        String first = Instant.now().toString();
        Map<String, String> fileIds = projects.fileIdMap(projectId);
        entries.batchUpsert(projectId, fileIds,
                List.of(entry("pack-one.csv", "10", 1, "Hello", "Перевод", "needs_human_review")), first);
        String id = entries.findByCell(projectId, EntryIds.ofCell("pack-one.csv", "10", 1)).getUuid();
        String second = Instant.now().toString();
        entries.batchUpsert(projectId, fileIds,
                List.of(entry("pack-one.csv", "10", 1, "Hello", "Перевод", "needs_human_review")), second);
        entries.deleteStale(projectId, second);
        assertEquals(id, entries.findByCell(projectId, EntryIds.ofCell("pack-one.csv", "10", 1)).getUuid());
    }

    @Test
    @DisplayName("сводка проектов живыми запросами")
    void liveSummaries() {
        String now = Instant.now().toString();
        Map<String, String> fileIds = projects.fileIdMap(projectId);
        entries.batchUpsert(projectId, fileIds,
                List.of(entry("pack-one.csv", "10", 1, "Hello", "Привет", "needs_human_review")), now);
        var all = projects.summaries();
        assertEquals(1, all.size());
        var summary = projects.summarize(projectId);
        assertEquals(1L, summary.entries());
        assertEquals(1L, summary.translated());
        assertEquals(Map.of("needs_human_review", 1L), summary.byStatus());
        assertEquals(summary, all.get(projectId.toString()));
    }

    @Test
    @DisplayName("id, не совпадающий с ячейкой, отклоняется")
    void rejectsCellMismatch() {
        String now = Instant.now().toString();
        Map<String, String> fileIds = projects.fileIdMap(projectId);
        TranslationEntry bad = entry("pack-one.csv", "10", 1, "Hello", "", "untranslated");
        bad.setId("c_deadbeefdeadbeef");
        assertThrows(IllegalArgumentException.class,
                () -> entries.batchUpsert(projectId, fileIds, List.of(bad), now));
    }

    @Test
    @DisplayName("обновление перевода и история")
    void updateAndHistory() {
        String now = Instant.now().toString();
        Map<String, String> fileIds = projects.fileIdMap(projectId);
        entries.batchUpsert(projectId, fileIds,
                List.of(entry("pack-one.csv", "10", 1, "Hello", "", "untranslated")), now);
        String cellId = EntryIds.ofCell("pack-one.csv", "10", 1);
        String id = entries.findByCell(projectId, cellId).getUuid();
        assertEquals(1, entries.updateTranslation(id, "Привет", "needs_human_review", now));
        assertEquals("Привет", entries.findById(UUID.fromString(id)).getTranslation());
        entries.insertHistory(projectId, id, "", "untranslated", "Привет", "needs_human_review", "editor", now);
        assertEquals(1L, projects.summarize(projectId).translated());
    }

    @Test
    @DisplayName("прогресс по файлам одним запросом")
    void progressByFile() {
        String now = Instant.now().toString();
        Map<String, String> fileIds = projects.fileIdMap(projectId);
        entries.batchUpsert(projectId, fileIds, new ArrayList<>(List.of(
                entry("pack-one.csv", "10", 1, "Hello", "Привет", "needs_human_review"),
                entry("pack-one.csv", "11", 1, "Bye", "", "untranslated"))), now);
        var rows = entries.progressByFile(projectId);
        assertEquals(1, rows.size());
        assertEquals("pack-one.csv", rows.get(0).get("file"));
        assertEquals(2L, ((Number) rows.get(0).get("total")).longValue());
        assertEquals(1L, ((Number) rows.get(0).get("done")).longValue());
    }

    @Test
    @DisplayName("пак: сохранение и чтение")
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
    @DisplayName("прогоны сборки: старт и финиш")
    void mergeRunLifecycle() {
        String runId = merges.start(projectId, Instant.now().toString(), "in", "out", 3);
        assertNotNull(runId);
        merges.finish(runId, Instant.now().toString(), "completed", 3, 10, "[]");
        assertEquals("completed", merges.last(projectId).get("status"));
    }
}
