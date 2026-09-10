package com.harmoniasuite.service.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmoniasuite.TestDatabases;
import com.harmoniasuite.domain.EntryIds;
import com.harmoniasuite.domain.TranslationDocument;
import com.harmoniasuite.domain.TranslationEntry;
import com.harmoniasuite.dto.RunRequest;
import com.harmoniasuite.repository.EntryRepository;
import com.harmoniasuite.repository.MergeRunRepository;
import com.harmoniasuite.repository.PackRepository;
import com.harmoniasuite.repository.ProjectRepository;
import com.harmoniasuite.service.export.MergeService;
import com.harmoniasuite.service.export.PackManifestService;
import com.harmoniasuite.service.job.ExtractJobHandler;
import com.harmoniasuite.service.job.JobPaths;
import com.harmoniasuite.util.CsvSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchWorkflowTest {

    private final CsvSupport csv = new CsvSupport();

    @TempDir
    Path dbDir;

    private ProjectRepository projectRepository;
    private EntryRepository entryRepository;
    private ExtractService extract;
    private MergeService merge;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = TestDatabases.sqlite(dbDir);
        projectRepository = new ProjectRepository(jdbc);
        entryRepository = new EntryRepository(jdbc);
        PackRepository packRepository = new PackRepository(jdbc);
        MergeRunRepository mergeRuns = new MergeRunRepository(jdbc);
        extract = new ExtractService(csv, projectRepository, entryRepository,
                new DataSourceTransactionManager(jdbc.getDataSource()));
        merge = new MergeService(csv, projectRepository, entryRepository, packRepository,
                new PackManifestService(), mergeRuns, new ObjectMapper().findAndRegisterModules());
    }

    private UUID createProject(Path dir) {
        return projectRepository.insert("proj", "", dir.toString(),
                dir.resolve("exported_csv").toString(), "en", "ru", Instant.now().toString());
    }

    private void persist(UUID projectId, Map<String, TranslationEntry> entriesBySource) {
        String now = Instant.now().toString();
        for (TranslationEntry e : entriesBySource.values()) {
            TranslationEntry stored = entryRepository.findByCell(projectId, e.getId());
            entryRepository.updateTranslation(stored.getUuid(),
                    e.getTranslation() == null ? "" : e.getTranslation(),
                    e.getStatus() == null ? "untranslated" : e.getStatus(), now);
        }
    }

    private static void writeCsv(Path file, List<String> dataRows) throws Exception {
        writeCsvFull(file, List.of(
                "key,#,0",
                "0,Name,Text",
                "key,#,0",
                "Int32,Int32,String"), dataRows);
    }

    private static void writeCsvFull(Path file, List<String> headerRows, List<String> dataRows) throws Exception {
        List<String> rows = new ArrayList<>(headerRows);
        rows.addAll(dataRows);
        Files.writeString(file, String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
    }

    private static Map<String, TranslationEntry> bySource(TranslationDocument document) {
        return document.getEntries().stream()
                .collect(Collectors.toMap(TranslationEntry::getSource, e -> e));
    }

    @Test
    @DisplayName("unchanged re-extract skips, force runs to the end")
    void reextractWithoutChangesSkipsUnlessForced(@TempDir Path tmp) throws Exception {
        Path input = tmp.resolve("in");
        Files.createDirectories(input);
        UUID projectId = createProject(tmp.resolve("proj"));
        List<String> log = new ArrayList<>();
        writeCsv(input.resolve("Action.csv"), List.of("1,Attack,Deal damage to target."));
        JobPaths paths = new JobPaths(projectId, tmp.resolve("proj"), input, tmp.resolve("out"));
        ExtractJobHandler handler = new ExtractJobHandler(extract);
        handler.execute(new RunRequest("extract", projectId, null, null, null, false, null, null), paths, log::add);
        assertTrue(log.stream().noneMatch(l -> l.contains("пропуск")));
        log.clear();
        handler.execute(new RunRequest("extract", projectId, null, null, null, false, null, null), paths, log::add);
        assertTrue(log.stream().anyMatch(l -> l.contains("пропуск")));
        log.clear();
        handler.execute(new RunRequest("extract", projectId, null, null, null, true, null, null), paths, log::add);
        assertTrue(log.stream().noneMatch(l -> l.contains("пропуск")));
        assertTrue(log.stream().anyMatch(l -> l.contains("Проект сохранён")));
    }

    @Test
    @DisplayName("auto touches only changed files, foreign ones intact")
    void autoTouchesOnlyChanged(@TempDir Path tmp) throws Exception {
        Path input = tmp.resolve("in");
        Files.createDirectories(input);
        UUID projectId = createProject(tmp.resolve("proj"));
        List<String> log = new ArrayList<>();
        writeCsv(input.resolve("First.csv"), List.of("1,Attack,Deal damage to target."));
        writeCsv(input.resolve("Second.csv"), List.of("1,Heal,Restore vigor."));
        Map<String, TranslationEntry> base =
                bySource(extract.syncSourcesAuto(input, projectId, log::add));
        base.get("Deal damage to target.").setTranslation("Нанести урон.");
        base.get("Deal damage to target.").setStatus("human_reviewed");
        base.get("Restore vigor.").setTranslation("Восстановить силы.");
        base.get("Restore vigor.").setStatus("human_reviewed");
        persist(projectId, base);
        String firstCell = EntryIds.ofCell("First.csv", "1", 2);
        String firstUpdated = entryRepository.findByCell(projectId, firstCell).getUpdatedAt();
        writeCsv(input.resolve("Second.csv"), List.of(
                "1,Heal,Restore vigor.", "2,Teleport,Travel far."));
        log.clear();
        extract.syncSourcesAuto(input, projectId, log::add);
        assertEquals(firstUpdated,
                entryRepository.findByCell(projectId, firstCell).getUpdatedAt());
        assertEquals("Нанести урон.",
                entryRepository.findByCell(projectId, firstCell).getTranslation());
        assertEquals("Travel far.", entryRepository
                .findByCell(projectId, EntryIds.ofCell("Second.csv", "2", 2)).getSource());
        assertTrue(log.stream().anyMatch(l -> l.contains("Изменённых файлов: 1")));
    }

    @Test
    @DisplayName("auto deletes only its own vanished file")
    void autoVanishedDeletesOnlyItsOwn(@TempDir Path tmp) throws Exception {
        Path input = tmp.resolve("in");
        Files.createDirectories(input);
        UUID projectId = createProject(tmp.resolve("proj"));
        List<String> log = new ArrayList<>();
        writeCsv(input.resolve("First.csv"), List.of("1,Attack,Deal damage to target."));
        writeCsv(input.resolve("Second.csv"), List.of("1,Heal,Restore vigor."));
        extract.syncSourcesAuto(input, projectId, log::add);
        Files.delete(input.resolve("Second.csv"));
        extract.syncSourcesAuto(input, projectId, log::add);
        assertNull(entryRepository.findByCell(projectId, EntryIds.ofCell("Second.csv", "1", 2)));
        assertTrue(entryRepository.findByCell(projectId, EntryIds.ofCell("First.csv", "1", 2)) != null);
        assertEquals(1, projectRepository.files(projectId).size());
    }

    @Test
    @DisplayName("auto skips without changes")
    void autoWithoutChangesSkips(@TempDir Path tmp) throws Exception {
        Path input = tmp.resolve("in");
        Files.createDirectories(input);
        UUID projectId = createProject(tmp.resolve("proj"));
        List<String> log = new ArrayList<>();
        writeCsv(input.resolve("First.csv"), List.of("1,Attack,Deal damage to target."));
        extract.syncSourcesAuto(input, projectId, log::add);
        log.clear();
        TranslationDocument again = extract.syncSourcesAuto(input, projectId, log::add);
        assertTrue(again.getEntries().isEmpty());
        assertTrue(log.stream().anyMatch(l -> l.contains("Изменений нет")));
    }

    @Test
    @DisplayName("auto catches same-size edits via hash")
    void autoDetectsSameSizeChange(@TempDir Path tmp) throws Exception {
        Path input = tmp.resolve("in");
        Files.createDirectories(input);
        UUID projectId = createProject(tmp.resolve("proj"));
        List<String> log = new ArrayList<>();
        writeCsv(input.resolve("First.csv"), List.of("1,Attack,Deal damage to target."));
        Map<String, TranslationEntry> base =
                bySource(extract.syncSourcesAuto(input, projectId, log::add));
        base.get("Deal damage to target.").setTranslation("Нанести урон.");
        base.get("Deal damage to target.").setStatus("human_reviewed");
        persist(projectId, base);
        writeCsv(input.resolve("First.csv"), List.of("1,Attack,Deal damage to TARGET."));
        log.clear();
        TranslationDocument v2 = extract.syncSourcesAuto(input, projectId, log::add);
        TranslationEntry changed = bySource(v2).get("Deal damage to TARGET.");
        assertEquals("Нанести урон.", changed.getTranslation());
        assertEquals("stale", changed.getStatus());
        assertTrue(log.stream().anyMatch(l -> l.contains("Изменённых файлов: 1")));
    }

    @Test
    @DisplayName("patch carryover, stale, new and deleted rows")
    void reextractCarriesStaleAndNew(@TempDir Path tmp) throws Exception {
        Path input = tmp.resolve("in");
        Files.createDirectories(input);
        UUID projectId = createProject(tmp.resolve("proj"));
        List<String> log = new ArrayList<>();

        writeCsv(input.resolve("Action.csv"), List.of(
                "1,Attack,Deal damage to target.",
                "2,Heal,Restore HP.",
                "4,Old,Old removed text."));
        TranslationDocument v1 = extract.syncSources(input, projectId, List.of("Action.csv"), log::add);
        assertEquals(3, v1.getEntries().size());

        Map<String, TranslationEntry> first = bySource(v1);
        first.get("Restore HP.").setTranslation("Восстановить HP.");
        first.get("Restore HP.").setStatus("approved");
        first.get("Deal damage to target.").setTranslation("Нанести урон.");
        first.get("Deal damage to target.").setStatus("machine_translated");
        persist(projectId, first);

        writeCsv(input.resolve("Action.csv"), List.of(
                "3,Teleport,Travel to aetheryte.",
                "1,Attack,Deal heavy damage to target.",
                "2,Heal,Restore HP."));
        TranslationDocument v2 = extract.syncSources(input, projectId, List.of("Action.csv"), log::add);
        Map<String, TranslationEntry> second = bySource(v2);

        assertEquals(3, second.size());
        assertNull(second.get("Old removed text."));

        TranslationEntry healed = second.get("Restore HP.");
        assertEquals("Восстановить HP.", healed.getTranslation());
        assertEquals("approved", healed.getStatus());

        TranslationEntry attack = second.get("Deal heavy damage to target.");
        assertEquals("Нанести урон.", attack.getTranslation());
        assertEquals("stale", attack.getStatus());

        TranslationEntry teleport = second.get("Travel to aetheryte.");
        assertEquals("", teleport.getTranslation());
        assertEquals("untranslated", teleport.getStatus());

        assertTrue(log.stream().anyMatch(l -> l.contains("Перенесено переводов: 1")
                && l.contains("устарело: 1") && l.contains("непереведённых: 1")));

        long attackRow = v2.getEntries().stream()
                .filter(e -> e.getId().equals(attack.getId()))
                .mapToInt(TranslationEntry::getRowIndex).findFirst().orElseThrow();
        assertEquals(5, attackRow);
    }

    @Test
    @DisplayName("merge skips stale")
    void mergeSkipsStale(@TempDir Path tmp) throws Exception {
        Path input = tmp.resolve("in");
        Files.createDirectories(input);
        UUID projectId = createProject(tmp.resolve("proj"));
        Path output = tmp.resolve("out");
        List<String> log = new ArrayList<>();

        writeCsv(input.resolve("Action.csv"), List.of(
                "1,Attack,Deal heavy damage to target.",
                "2,Heal,Restore HP."));
        TranslationDocument doc = extract.syncSources(input, projectId, List.of("Action.csv"), log::add);
        Map<String, TranslationEntry> entries = bySource(doc);
        entries.get("Restore HP.").setTranslation("Восстановить HP.");
        entries.get("Restore HP.").setStatus("machine_translated");
        entries.get("Deal heavy damage to target.").setTranslation("Нанести урон.");
        entries.get("Deal heavy damage to target.").setStatus("stale");
        persist(projectId, entries);

        log.clear();
        merge.merge(input, output, projectId, log::add);

        List<String> lines = Files.readAllLines(output.resolve("Action.csv"), StandardCharsets.UTF_8);
        assertTrue(lines.stream().anyMatch(l -> l.contains("Восстановить HP.")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("Deal heavy damage to target.")));
        assertTrue(log.stream().anyMatch(l -> l.contains("Устаревших переводов пропущено: 1")));
        assertTrue(log.stream().anyMatch(l -> l.contains("1 с переводом, 0 без, 1 устарело")));
    }

    @Test
    @DisplayName("column insert causes no false stale")
    void columnInsertPreventsFalseStale(@TempDir Path tmp) throws Exception {
        Path input = tmp.resolve("in");
        Files.createDirectories(input);
        UUID projectId = createProject(tmp.resolve("proj"));
        List<String> log = new ArrayList<>();

        writeCsvFull(input.resolve("Action.csv"), List.of(
                "key,#,0",
                "0,Id,Name,Text",
                "key,#,0",
                "Int32,String,String"), List.of("1,NameA,TextA"));
        TranslationDocument v1 = extract.syncSources(input, projectId, List.of("Action.csv"), log::add);
        Map<String, TranslationEntry> first = bySource(v1);
        first.get("TextA").setTranslation("ТекстА.");
        first.get("TextA").setStatus("machine_translated");
        persist(projectId, first);

        writeCsvFull(input.resolve("Action.csv"), List.of(
                "key,#,0",
                "0,Id,Label,Name,Text",
                "key,#,0",
                "Int32,String,String,String"), List.of("1,ExtraA,ChangedB,TextA"));
        TranslationDocument v2 = extract.syncSources(input, projectId, List.of("Action.csv"), log::add);
        Map<String, TranslationEntry> second = bySource(v2);

        assertEquals("ТекстА.", second.get("TextA").getTranslation());
        TranslationEntry changed = second.get("ChangedB");
        assertEquals("", changed.getTranslation());
        assertEquals("untranslated", changed.getStatus());
        assertTrue(second.values().stream().noneMatch(e -> "stale".equals(e.getStatus())));
    }

    @Test
    @DisplayName("merge skips cells with changed source")
    void mergeSkipsChangedSource(@TempDir Path tmp) throws Exception {
        Path input = tmp.resolve("in");
        Files.createDirectories(input);
        UUID projectId = createProject(tmp.resolve("proj"));
        Path output = tmp.resolve("out");
        List<String> log = new ArrayList<>();

        writeCsv(input.resolve("Action.csv"), List.of(
                "1,Attack,Deal damage to target.",
                "2,Heal,Restore HP."));
        TranslationDocument doc = extract.syncSources(input, projectId, List.of("Action.csv"), log::add);
        Map<String, TranslationEntry> entries = bySource(doc);
        entries.get("Deal damage to target.").setTranslation("Нанести урон.");
        entries.get("Deal damage to target.").setStatus("machine_translated");
        entries.get("Restore HP.").setTranslation("Восстановить HP.");
        entries.get("Restore HP.").setStatus("machine_translated");
        persist(projectId, entries);

        writeCsv(input.resolve("Action.csv"), List.of(
                "1,Attack,Changed attack text.",
                "2,Heal,Restore HP."));
        log.clear();
        merge.merge(input, output, projectId, log::add);

        List<String> lines = Files.readAllLines(output.resolve("Action.csv"), StandardCharsets.UTF_8);
        assertTrue(lines.stream().anyMatch(l -> l.contains("Восстановить HP.")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("Changed attack text.")));
        assertTrue(log.stream().anyMatch(l -> l.contains("исходник не совпадает")));
    }

    @Test
    @DisplayName("merge skips columns turned non-String")
    void mergeSkipsColumnTurnedNonString(@TempDir Path tmp) throws Exception {
        Path input = tmp.resolve("in");
        Files.createDirectories(input);
        UUID projectId = createProject(tmp.resolve("proj"));
        Path output = tmp.resolve("out");
        List<String> log = new ArrayList<>();

        writeCsvFull(input.resolve("Action.csv"), List.of(
                "key,#,0",
                "0,Id,Name",
                "key,#,0",
                "Int32,String"), List.of("1,NameA"));
        TranslationDocument doc = extract.syncSources(input, projectId, List.of("Action.csv"), log::add);
        Map<String, TranslationEntry> entries = bySource(doc);
        entries.get("NameA").setTranslation("ИмяА.");
        entries.get("NameA").setStatus("machine_translated");
        persist(projectId, entries);

        writeCsvFull(input.resolve("Action.csv"), List.of(
                "key,#,0",
                "0,Id,Name",
                "key,#,0",
                "Int32,Int32"), List.of("1,NameA"));
        log.clear();
        merge.merge(input, output, projectId, log::add);

        assertFalse(Files.exists(output.resolve("Action.csv")));
        assertTrue(log.stream().anyMatch(l -> l.contains("исходник не совпадает")));
    }

    @Test
    @DisplayName("merge writes only files with translations")
    void mergeWritesOnlyTranslatedFiles(@TempDir Path tmp) throws Exception {
        Path input = tmp.resolve("in");
        Files.createDirectories(input);
        UUID projectId = createProject(tmp.resolve("proj"));
        Path output = tmp.resolve("out");
        List<String> log = new ArrayList<>();

        writeCsv(input.resolve("Translated.csv"), List.of("1,Attack,Deal damage to target."));
        writeCsv(input.resolve("Untranslated.csv"), List.of("1,Idle,Do nothing at all."));
        TranslationDocument doc = extract.syncSources(input, projectId,
                List.of("Translated.csv", "Untranslated.csv"), log::add);
        Map<String, TranslationEntry> entries = bySource(doc);
        entries.get("Deal damage to target.").setTranslation("Нанести урон.");
        entries.get("Deal damage to target.").setStatus("machine_translated");
        persist(projectId, entries);

        log.clear();
        merge.merge(input, output, projectId, log::add);

        assertTrue(Files.isRegularFile(output.resolve("Translated.csv")));
        assertFalse(Files.exists(output.resolve("Untranslated.csv")));
        assertTrue(log.stream().anyMatch(l -> l.contains("без переводов пропущено файлов: 1")));
    }

    @Test
    @DisplayName("merge without translations writes nothing")
    void mergeWithoutTranslationsWritesNothing(@TempDir Path tmp) throws Exception {
        Path input = tmp.resolve("in");
        Files.createDirectories(input);
        UUID projectId = createProject(tmp.resolve("proj"));
        Path output = tmp.resolve("out");
        List<String> log = new ArrayList<>();

        writeCsv(input.resolve("First.csv"), List.of("1,Attack,Deal damage to target."));
        writeCsv(input.resolve("Second.csv"), List.of("1,Idle,Do nothing at all."));
        extract.syncSources(input, projectId, List.of("First.csv", "Second.csv"), log::add);

        log.clear();
        merge.merge(input, output, projectId, log::add);

        assertFalse(Files.exists(output.resolve("First.csv")));
        assertFalse(Files.exists(output.resolve("Second.csv")));
        assertTrue(log.stream().anyMatch(l -> l.contains("без переводов пропущено файлов: 2")));
    }
}
