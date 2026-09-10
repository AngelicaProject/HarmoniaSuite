package com.harmoniasuite.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmoniasuite.domain.EntryQuery;
import com.harmoniasuite.domain.PackMeta;
import com.harmoniasuite.domain.TranslationEntry;
import com.harmoniasuite.repository.EntryRepository;
import com.harmoniasuite.repository.MergeRunRepository;
import com.harmoniasuite.repository.PackRepository;
import com.harmoniasuite.repository.ProjectRepository;
import com.harmoniasuite.util.CsvSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

@Service
public class MergeService {

    private final CsvSupport csv;
    private final ProjectRepository projectRepository;
    private final EntryRepository entryRepository;
    private final PackRepository packs;
    private final PackManifestService manifest;
    private final MergeRunRepository merges;
    private final ObjectMapper objectMapper;

    public MergeService(CsvSupport csv, ProjectRepository projectRepository, EntryRepository entryRepository,
            PackRepository packs, PackManifestService manifest,
            MergeRunRepository merges, ObjectMapper objectMapper) {
        this.csv = csv;
        this.projectRepository = projectRepository;
        this.entryRepository = entryRepository;
        this.packs = packs;
        this.manifest = manifest;
        this.merges = merges;
        this.objectMapper = objectMapper;
    }

    public void merge(Path inputDir, Path outputDir, UUID projectId, Consumer<String> log) throws IOException {
        merge(inputDir, outputDir, projectId, List.of(), log);
    }

    public void merge(Path inputDir, Path outputDir, UUID projectId, List<String> tables,
            Consumer<String> log) throws IOException {
        ProjectRepository.ProjectRow project = projectRepository.findById(projectId);
        Path inputRoot = inputDir.toAbsolutePath().normalize();
        Path outputRoot = outputDir.toAbsolutePath().normalize();

        List<String> selectedFiles = resolveSelection(projectId, tables);
        Map<String, String> fileIds = projectRepository.fileIdMap(projectId);
        Set<String> translatable = translatableFiles(projectId);
        List<String> toBuild = selectedFiles.stream()
                .filter(f -> translatable.contains(f.replace('\\', '/')))
                .distinct().sorted().toList();
        int skippedNoTranslations = selectedFiles.size() - toBuild.size();

        String runId = merges.start(projectId, Instant.now().toString(),
                inputRoot.toString(), outputRoot.toString(), selectedFiles.size());
        log.accept("  Файлов с переводами к сборке: " + toBuild.size() + " из " + selectedFiles.size());

        List<FileOutcome> outcomes = new ArrayList<>();
        try {
            for (String relative : toBuild) {
                outcomes.add(buildFile(projectId, inputRoot, outputRoot, fileIds, relative, log));
            }
            int pruned = pruneStaleOutputs(outputRoot, builtNames(outcomes));
            if (pruned > 0) {
                log.accept("→ Из вывода удалено устаревших файлов: " + pruned);
            }
            writeManifest(projectId, project.name(), outputRoot, log);
            String now = Instant.now().toString();
            merges.finish(runId, now, hasErrors(outcomes) ? "completed_with_errors" : "completed",
                    builtCount(outcomes), translatedCells(outcomes), toJson(outcomes));
        } catch (RuntimeException e) {
            merges.finish(runId, Instant.now().toString(), "failed",
                    builtCount(outcomes), translatedCells(outcomes), toJson(outcomes));
            throw e;
        }
        logSummary(projectId, selectedFiles.size(), skippedNoTranslations, outcomes, outputRoot, log);
    }

    private List<String> resolveSelection(UUID projectId, List<String> tables) {
        List<String> allFiles = projectRepository.files(projectId).stream()
                .map(ProjectRepository.FileRow::path).distinct().sorted().toList();
        if (tables == null || tables.isEmpty()) {
            return allFiles;
        }
        return tables.stream().filter(allFiles::contains).distinct().sorted().toList();
    }

    private Set<String> translatableFiles(UUID projectId) {
        Set<String> files = new HashSet<>();
        for (String path : entryRepository.translatedFilePaths(projectId)) {
            files.add(path == null ? "" : path.replace('\\', '/'));
        }
        return files;
    }

    private FileOutcome buildFile(UUID projectId, Path inputRoot, Path outputRoot,
            Map<String, String> fileIds, String relative, Consumer<String> log) throws IOException {
        Path sourcePath = inputRoot.resolve(relative);
        if (!Files.isRegularFile(sourcePath)) {
            return fail(relative, "File not found: " + sourcePath, log);
        }
        try {
            List<List<String>> original = csv.readRows(sourcePath);
            List<List<String>> changed = deepCopy(original);
            Map<CellKey, TranslationEntry> fileCells = loadCells(projectId, fileIds, relative);
            ApplyResult applied = fileCells.isEmpty()
                    ? ApplyResult.EMPTY
                    : applyTranslations(changed, fileCells, relative, log);
            if (applied.translated() == 0) {
                return FileOutcome.skipped(relative);
            }
            csv.validateStructure(original, changed);
            csv.writeRowsAtomic(outputRoot.resolve(relative), changed);
            return new FileOutcome(relative, "ok", applied.translated(), applied.mismatched(),
                    applied.stale(), null);
        } catch (Exception e) {
            return fail(relative, e.getMessage(), log);
        }
    }

    private Map<CellKey, TranslationEntry> loadCells(UUID projectId, Map<String, String> fileIds,
            String relative) {
        Map<CellKey, TranslationEntry> fileCells = new HashMap<>();
        String fileId = fileIds.get(relative);
        if (fileId == null) {
            return fileCells;
        }
        for (TranslationEntry e : entryRepository.translatedByFile(projectId, fileId)) {
            fileCells.put(new CellKey(
                    e.getFile() == null ? "" : e.getFile().replace('\\', '/'),
                    e.getRowKey() == null ? "" : e.getRowKey(),
                    e.getColumnIndex()), e);
        }
        return fileCells;
    }

    private ApplyResult applyTranslations(List<List<String>> changed,
            Map<CellKey, TranslationEntry> fileCells, String relative, Consumer<String> log) {
        int translated = 0;
        int mismatched = 0;
        int stale = 0;
        Set<Integer> stringCols = new HashSet<>(csv.stringColumns(changed));
        for (int rowIndex = CsvSupport.DATA_START_ROW; rowIndex < changed.size(); rowIndex++) {
            List<String> csvRow = changed.get(rowIndex);
            if (csvRow.isEmpty()) {
                continue;
            }
            String rowKey = csvRow.getFirst();
            for (int columnIndex = 0; columnIndex < csvRow.size(); columnIndex++) {
                TranslationEntry entry = fileCells.get(new CellKey(relative, rowKey, columnIndex));
                if (entry == null || !stringCols.contains(columnIndex)) {
                    if (entry != null) {
                        mismatched++;
                    }
                    continue;
                }
                String translation = entry.getTranslation();
                if (translation == null || translation.isBlank()) {
                    continue;
                }
                if ("stale".equals(entry.getStatus())) {
                    stale++;
                    continue;
                }
                if (!csvRow.get(columnIndex).equals(entry.getSource())) {
                    mismatched++;
                    continue;
                }
                String replacement = csv.preserveOuterWhitespace(csvRow.get(columnIndex), translation);
                if (!replacement.equals(csvRow.get(columnIndex))) {
                    csvRow.set(columnIndex, replacement);
                    translated++;
                }
            }
        }
        if (mismatched > 0) {
            log.accept("ERROR: " + relative + ": " + mismatched
                    + " ячеек пропущено — исходник не совпадает с проектом, запустите extract заново");
        }
        return new ApplyResult(translated, mismatched, stale);
    }

    private FileOutcome fail(String relative, String error, Consumer<String> log) {
        log.accept("ERROR: " + relative + ": " + error);
        return new FileOutcome(relative, "error", 0, 0, 0, error);
    }

    private int pruneStaleOutputs(Path outputRoot, Set<String> builtNames) throws IOException {
        if (!Files.isDirectory(outputRoot)) {
            return 0;
        }
        int pruned = 0;
        try (var walk = Files.walk(outputRoot)) {
            for (Path file : walk.filter(p -> Files.isRegularFile(p)
                    && p.toString().toLowerCase().endsWith(".csv")).toList()) {
                String rel = outputRoot.relativize(file).toString().replace('\\', '/');
                if (!builtNames.contains(rel)) {
                    Files.deleteIfExists(file);
                    pruned++;
                }
            }
        }
        return pruned;
    }

    private void logSummary(UUID projectId, int selectedTotal, int skippedNoTranslations,
            List<FileOutcome> outcomes, Path outputRoot, Consumer<String> log) {
        boolean failed = hasErrors(outcomes);
        long withTrans = entryRepository.translatedCount(projectId);
        long noTrans = entryRepository.countByQuery(projectId,
                new EntryQuery(null, List.of("no_translation_required"), null, null, false));
        long staleCount = entryRepository.countByQuery(projectId,
                new EntryQuery(null, List.of("stale"), null, null, false));
        long total = entryRepository.count(projectId, null);
        long without = total - withTrans - staleCount - noTrans;
        int skipped = skippedNoTranslations + skippedCount(outcomes);
        int staleSkipped = staleCells(outcomes);
        int coordMismatch = mismatchedCells(outcomes);
        log.accept("✓ Сборка завершена: " + (failed ? "completed_with_errors" : "completed"));
        log.accept("  Файлов: " + builtCount(outcomes) + "/" + selectedTotal
                + " | ячеек переведено: " + translatedCells(outcomes)
                + " | фраз: " + withTrans + " с переводом, " + without + " без"
                + (noTrans > 0 ? ", " + noTrans + " без перевода" : "")
                + (staleCount > 0 ? ", " + staleCount + " устарело" : "")
                + (skipped > 0 ? " | без переводов пропущено файлов: " + skipped : ""));
        if (staleSkipped > 0) {
            log.accept("→ Устаревших переводов пропущено: " + staleSkipped
                    + " — проверьте в редакторе (статус stale)");
        }
        if (coordMismatch > 0) {
            log.accept("→ Пропущено из-за расхождения координат: " + coordMismatch + " — запустите extract заново");
        }
        log.accept("  Вывод: " + outputRoot + (failed ? " (есть ошибки)" : ""));
        if (translatedCells(outcomes) == 0) {
            log.accept("→ Нет переведённых ячеек — проверьте редактор / AI-перевод");
        } else {
            log.accept("→ Готово к проверке: вкладка Экспорт → Собрать и скачать пак");
        }
    }

    private static Set<String> builtNames(List<FileOutcome> outcomes) {
        Set<String> names = new HashSet<>();
        for (FileOutcome outcome : outcomes) {
            if ("ok".equals(outcome.status())) {
                names.add(outcome.file());
            }
        }
        return names;
    }

    private static boolean hasErrors(List<FileOutcome> outcomes) {
        return outcomes.stream().anyMatch(o -> "error".equals(o.status()));
    }

    private static int builtCount(List<FileOutcome> outcomes) {
        return (int) outcomes.stream().filter(o -> "ok".equals(o.status())).count();
    }

    private static int skippedCount(List<FileOutcome> outcomes) {
        return (int) outcomes.stream().filter(o -> "skipped".equals(o.status())).count();
    }

    private static int translatedCells(List<FileOutcome> outcomes) {
        return outcomes.stream().mapToInt(FileOutcome::translatedCells).sum();
    }

    private static int mismatchedCells(List<FileOutcome> outcomes) {
        return outcomes.stream().mapToInt(FileOutcome::mismatchedCells).sum();
    }

    private static int staleCells(List<FileOutcome> outcomes) {
        return outcomes.stream().mapToInt(FileOutcome::staleCells).sum();
    }

    private String toJson(List<FileOutcome> outcomes) {
        try {
            List<Map<String, Object>> fileResults = new ArrayList<>(outcomes.size());
            for (FileOutcome outcome : outcomes) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("file", outcome.file());
                result.put("status", outcome.status());
                result.put("translated_cells", outcome.translatedCells());
                if (outcome.error() != null) {
                    result.put("error", outcome.error());
                }
                fileResults.add(result);
            }
            return objectMapper.writeValueAsString(fileResults);
        } catch (Exception e) {
            return "[]";
        }
    }

    private void writeManifest(UUID projectId, String projectName, Path outputRoot, Consumer<String> log) {
        PackMeta pack = manifest.effectivePack(packs.load(projectId), projectName);
        List<String> errors = manifest.validate(pack);
        if (!errors.isEmpty()) {
            log.accept("⚠ manifest.json не записан — заполните вкладку «Пак»:");
            errors.forEach(err -> log.accept("  • " + err));
            return;
        }
        try {
            manifest.write(outputRoot, manifest.build(pack));
            log.accept("✓ manifest.json записан: " + outputRoot.resolve(PackManifestService.MANIFEST_FILE_NAME));
        } catch (Exception e) {
            log.accept("ERROR: manifest.json: " + e.getMessage());
        }
    }

    private static List<List<String>> deepCopy(List<List<String>> original) {
        List<List<String>> copy = new ArrayList<>(original.size());
        for (List<String> row : original) {
            copy.add(new ArrayList<>(row));
        }
        return copy;
    }

    private record ApplyResult(int translated, int mismatched, int stale) {
        static final ApplyResult EMPTY = new ApplyResult(0, 0, 0);
    }

    private record FileOutcome(String file, String status, int translatedCells, int mismatchedCells,
            int staleCells, String error) {
        static FileOutcome skipped(String file) {
            return new FileOutcome(file, "skipped", 0, 0, 0, null);
        }
    }

    private record CellKey(String file, String rowKey, int columnIndex) {
    }
}
