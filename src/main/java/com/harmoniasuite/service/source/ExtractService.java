package com.harmoniasuite.service.source;

import com.harmoniasuite.domain.EntryIds;
import com.harmoniasuite.domain.EntryStatusPolicy;
import com.harmoniasuite.domain.ProjectMeta;
import com.harmoniasuite.domain.TranslationDocument;
import com.harmoniasuite.domain.TranslationEntry;
import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import com.harmoniasuite.repository.EntryRepository;
import com.harmoniasuite.repository.ProjectRepository;
import com.harmoniasuite.util.CsvSupport;
import com.harmoniasuite.util.SourcesFingerprint;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ExtractService {

    private static final int BATCH = 20000;

    private final CsvSupport csv;
    private final ProjectRepository projectRepository;
    private final EntryRepository entryRepository;
    private final TransactionTemplate writeTx;

    public ExtractService(CsvSupport csv, ProjectRepository projectRepository,
            EntryRepository entryRepository, PlatformTransactionManager transactionManager) {
        this.csv = csv;
        this.projectRepository = projectRepository;
        this.entryRepository = entryRepository;
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    public TranslationDocument syncSources(Path inputRoot, UUID projectId, List<String> selectedFiles,
            Consumer<String> log) throws IOException {
        return runSync(inputRoot, projectId, selectedFiles, null, log);
    }

    public record ChangedFiles(
            List<Path> changed,
            List<String> vanished,
            Map<String, ProjectRepository.FileFingerprint> fingerprints,
            String treeFingerprint) {
    }

    private record RunScope(
            List<String> processed,
            List<String> vanished,
            Map<String, ProjectRepository.FileFingerprint> fingerprints,
            String treeFingerprint) {
    }

    public TranslationDocument syncSourcesAuto(Path inputRoot, UUID projectId, Consumer<String> log)
            throws IOException {
        Path root = inputRoot.toAbsolutePath().normalize();
        projectRepository.findById(projectId);
        ChangedFiles diff = detectChangedFiles(projectId, root);
        if (diff.changed().isEmpty() && diff.vanished().isEmpty()) {
            log.accept("  Изменений нет — пропуск");
            return TranslationDocument.empty();
        }
        log.accept("  Изменённых файлов: " + diff.changed().size()
                + (diff.vanished().isEmpty() ? "" : " | пропало: " + diff.vanished().size()));
        List<String> relatives =
                diff.changed().stream().map(p -> toRelative(root, p)).sorted().toList();
        return runSync(inputRoot, projectId, relatives,
                new RunScope(relatives, diff.vanished(), diff.fingerprints(), diff.treeFingerprint()), log);
    }

    public ChangedFiles detectChangedFiles(UUID projectId, Path root) throws IOException {
        List<Path> all = csv.findCsvFiles(root);
        Map<String, ProjectRepository.FileFingerprint> stored = projectRepository.fileFingerprints(projectId);
        Set<String> present = new HashSet<>();
        List<Path> changed = new ArrayList<>();
        Map<String, ProjectRepository.FileFingerprint> fingerprints = new LinkedHashMap<>();
        for (Path file : all) {
            Path absolute = file.toAbsolutePath().normalize();
            String relative = root.relativize(absolute).toString().replace('\\', '/');
            long size = Files.size(absolute);
            present.add(relative);
            ProjectRepository.FileFingerprint old = stored.get(relative);
            if (old != null && old.size() == size && !old.hash().isEmpty()
                    && crc32(absolute).equals(old.hash())) {
                continue;
            }
            changed.add(file);
            fingerprints.put(relative, new ProjectRepository.FileFingerprint(size, crc32(absolute)));
        }
        List<String> vanished = stored.keySet().stream()
                .filter(path -> !present.contains(path)).sorted().toList();
        return new ChangedFiles(changed.stream().sorted().toList(), vanished, fingerprints,
                SourcesFingerprint.of(all, root));
    }

    private static String crc32(Path file) throws IOException {
        CRC32 checksum = new CRC32();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) {
                checksum.update(buffer, 0, read);
            }
        }
        return Long.toHexString(checksum.getValue());
    }

    private TranslationDocument runSync(Path inputRoot, UUID projectId, List<String> selectedFiles,
            RunScope scope, Consumer<String> log) throws IOException {
        Path root = inputRoot.toAbsolutePath().normalize();
        ProjectRepository.ProjectRow project = projectRepository.findById(projectId);
        Path projectDir = Path.of(project.projectDir()).toAbsolutePath().normalize();
        String now = Instant.now().toString();

        List<Path> paths = resolveSelection(root, selectedFiles);
        log.accept("  Файлов к обработке: " + paths.size());
        String sourcesFingerprint = scope != null && scope.treeFingerprint() != null
                ? scope.treeFingerprint()
                : SourcesFingerprint.of(csv.findCsvFiles(root), root);
        log.accept("  Отпечаток исходников: " + shortFingerprint(sourcesFingerprint));
        CarryIndex carry = CarryIndex.of(entryRepository.translatedCells(projectId));
        ParsedSources parsed = parseSources(root, paths, carry, log);
        // Одна транзакция на всю запись: прокси не перехватывает внутренние вызовы,
        // поэтому @Transactional здесь не сработает — только программная транзакция.
        return writeTx.execute(status -> storeParsed(
                projectId, project, projectDir, root, parsed, sourcesFingerprint, now, log,
                scope));
    }

    private ParsedSources parseSources(Path root, List<Path> paths, CarryIndex carry,
            Consumer<String> log) {
        Map<String, TranslationEntry> cells = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> errored = new ArrayList<>();
        int carried = 0;
        int stale = 0;
        int created = 0;
        long parseStarted = System.currentTimeMillis();
        for (int fileNo = 0; fileNo < paths.size(); fileNo++) {
            Path path = paths.get(fileNo);
            try {
                List<List<String>> rows = csv.readRows(path);
                List<Integer> columns = csv.stringColumns(rows);
                List<String> names = rows.size() > 1 ? rows.get(1) : List.of();
                String relative = toRelative(root, path);
                for (int rowIndex = CsvSupport.DATA_START_ROW; rowIndex < rows.size(); rowIndex++) {
                    List<String> csvRow = rows.get(rowIndex);
                    if (csvRow.isEmpty()) {
                        continue;
                    }
                    String rowKey = csvRow.getFirst();
                    for (int columnIndex : columns) {
                        if (columnIndex >= csvRow.size() || !csv.isTranslatable(csvRow.get(columnIndex))) {
                            continue;
                        }
                        String source = csvRow.get(columnIndex);
                        String columnName = columnIndex < names.size()
                                ? names.get(columnIndex) : String.valueOf(columnIndex);
                        TranslationEntry cell = new TranslationEntry();
                        cell.setId(EntryIds.ofCell(relative, rowKey, columnIndex));
                        cell.setSource(source);
                        cell.setProtectedTokens(csv.protectedTokens(source));
                        cell.setFile(relative);
                        cell.setRowKey(rowKey);
                        cell.setColumnIndex(columnIndex);
                        cell.setColumnName(columnName);
                        cell.setRowIndex(rowIndex);
                        Carry decision = carry.resolve(cell.getId(), relative, source, columnName);
                        if (decision != null && decision.translation() != null) {
                            cell.setTranslation(decision.translation());
                            cell.setStatus(decision.status());
                            if (decision.stale()) {
                                stale++;
                            } else {
                                carried++;
                            }
                        } else {
                            cell.setTranslation("");
                            cell.setStatus(EntryStatusPolicy.UNTRANSLATED);
                            created++;
                        }
                        cells.put(cell.getId(), cell);
                    }
                }
                if (fileNo % 500 == 499) {
                    log.accept("… " + (fileNo + 1) + "/" + paths.size()
                            + " файлов, строк: " + cells.size());
                }
            } catch (Exception e) {
                errors.add(path + ": " + e.getMessage());
                errored.add(toRelative(root, path));
            }
        }
        log.accept("  Разбор CSV: " + cells.size() + " строк за "
                + (System.currentTimeMillis() - parseStarted) + " мс");
        List<String> relatives = paths.stream().map(p -> toRelative(root, p)).toList();
        return new ParsedSources(cells, relatives, errors, errored, carried, stale, created);
    }

    private TranslationDocument storeParsed(UUID projectId, ProjectRepository.ProjectRow project, Path projectDir, Path root,
            ParsedSources parsed, String sourcesFingerprint, String now, Consumer<String> log, RunScope scope) {
        projectRepository.updateMeta(projectId, root.toString(), project.projectDir(),
                project.outputDir(), project.sourceLocale(), project.targetLocale(),
                project.createdAt(), now);
        projectRepository.updateSourcesFingerprint(projectId, sourcesFingerprint, now);
        long writeStarted = System.currentTimeMillis();
        List<TranslationEntry> all = new ArrayList<>(parsed.cells().values());
        log.accept("  Пишу в БД: " + all.size() + " строк…");
        long phase = System.currentTimeMillis();
        projectRepository.upsertFiles(projectId, parsed.relatives(), now);
        Map<String, String> fileIds = projectRepository.fileIdMap(projectId);
        log.accept("  Файлы: " + (System.currentTimeMillis() - phase) + " мс");
        phase = System.currentTimeMillis();
        for (int i = 0; i < all.size(); i += BATCH) {
            entryRepository.batchUpsert(projectId, fileIds, all.subList(i, Math.min(i + BATCH, all.size())), now);
        }
        log.accept("  Записи: " + (System.currentTimeMillis() - phase) + " мс");
        phase = System.currentTimeMillis();
        if (scope == null) {
            entryRepository.deleteStale(projectId, now);
            projectRepository.deleteStaleFiles(projectId, now);
        } else {
            entryRepository.deleteStaleEntries(projectId, uuidsOf(fileIds, scope.processed()), now);
            List<UUID> vanishedIds = uuidsOf(fileIds, scope.vanished());
            entryRepository.deleteEntriesByFiles(projectId, vanishedIds);
            projectRepository.deleteFilesByPaths(projectId, scope.vanished());
            Map<String, ProjectRepository.FileFingerprint> ok = new LinkedHashMap<>(scope.fingerprints());
            ok.keySet().removeAll(parsed.erroredRelatives());
            projectRepository.updateFileFingerprints(projectId, ok, now);
        }
        log.accept("  Чистка: " + (System.currentTimeMillis() - phase) + " мс");
        log.accept("  Запись в БД за " + (System.currentTimeMillis() - writeStarted) + " мс");

        TranslationDocument document = TranslationDocument.empty();
        ProjectMeta meta = document.getProject();
        meta.setCreatedAt(project.createdAt());
        meta.setUpdatedAt(now);
        meta.setInputRoot(root.toString());
        meta.setProjectDir(project.projectDir());
        meta.setOutputDir(project.outputDir());
        document.setEntries(all);
        document.setFiles(new ArrayList<>(parsed.relatives()));

        long withTokens = all.stream()
                .filter(e -> e.getProtectedTokens() != null && !e.getProtectedTokens().isEmpty()).count();
        log.accept("✓ Проект сохранён: " + project.name());
        log.accept("  Файлов обработано: " + parsed.relatives().size());
        log.accept("  Строк: " + all.size() + " | с тегами: " + withTokens);
        if (!parsed.cells().isEmpty() && (parsed.carried() > 0 || parsed.stale() > 0)) {
            log.accept("  Перенесено переводов: " + parsed.carried() + " | устарело: " + parsed.stale()
                    + " | непереведённых: " + parsed.created());
        }
        long cyrillic = all.stream()
                .filter(e -> e.getSource() != null && e.getSource().codePoints().anyMatch(cp ->
                        (cp >= 0x0400 && cp <= 0x04FF) || cp == 0x0451 || cp == 0x0401))
                .count();
        if (cyrillic > 0) {
            log.accept("⚠ Фраз уже на русском: " + cyrillic + " — проверьте корень извлечения (должен быть rawexd/en)");
        }
        log.accept("  Путь: " + projectDir);
        if (!parsed.errors().isEmpty()) {
            log.accept("⚠ Ошибок: " + parsed.errors().size());
            parsed.errors().subList(0, Math.min(20, parsed.errors().size()))
                    .forEach(err -> log.accept("  • " + err));
            if (parsed.errors().size() > 20) {
                log.accept("…и ещё " + (parsed.errors().size() - 20) + " ошибок");
            }
        }
        if (all.isEmpty()) {
            log.accept("→ Нет переводимых строк — проверьте выбор файлов");
        } else {
            log.accept("→ Далее: перевести через Gemini или открыть Редактор");
        }
        return document;
    }

    private record ParsedSources(
            Map<String, TranslationEntry> cells,
            List<String> relatives,
            List<String> errors,
            List<String> erroredRelatives,
            int carried,
            int stale,
            int created) {
    }

    private record Carry(String translation, String status, boolean stale) {
    }

    private static final class CarryIndex {
        private final Map<String, EntryRepository.TranslatedCell> byCell = new LinkedHashMap<>();
        private final Map<String, EntryRepository.TranslatedCell> byFileSource = new LinkedHashMap<>();
        private final Map<String, EntryRepository.TranslatedCell> bySource = new LinkedHashMap<>();

        static CarryIndex of(List<EntryRepository.TranslatedCell> cells) {
            CarryIndex index = new CarryIndex();
            for (EntryRepository.TranslatedCell cell : cells) {
                index.byCell.put(cell.cellId(), cell);
                index.byFileSource.putIfAbsent(key(cell.file(), cell.source()), cell);
                index.bySource.putIfAbsent(cell.source(), cell);
            }
            return index;
        }

        Carry resolve(String cellId, String file, String source, String columnName) {
            EntryRepository.TranslatedCell anchor = byCell.get(cellId);
            if (anchor != null
                    && columnName.equals(anchor.columnName() == null ? "" : anchor.columnName())) {
                if (!source.equals(anchor.source())) {
                    return new Carry(anchor.translation(), EntryStatusPolicy.STALE, true);
                }
                return new Carry(anchor.translation(), effectiveStatus(anchor.status()), false);
            }
            EntryRepository.TranslatedCell fallback = byFileSource.get(key(file, source));
            if (fallback == null) {
                fallback = bySource.get(source);
            }
            if (fallback == null) {
                return null;
            }
            return new Carry(fallback.translation(), effectiveStatus(fallback.status()), false);
        }

        private static String effectiveStatus(String status) {
            return status == null || status.equals(EntryStatusPolicy.UNTRANSLATED)
                    ? EntryStatusPolicy.UNTRANSLATED : status;
        }

        private static String key(String file, String source) {
            return (file == null ? "" : file) + "\0" + (source == null ? "" : source);
        }
    }

    private List<Path> resolveSelection(Path inputRoot, List<String> selectedFiles) throws IOException {
        if (selectedFiles == null) {
            return csv.findCsvFiles(inputRoot);
        }
        List<Path> paths = new ArrayList<>();
        for (String relative : selectedFiles) {
            Path candidate = inputRoot.resolve(relative).toAbsolutePath().normalize();
            if (!candidate.startsWith(inputRoot)
                    || !candidate.getFileName().toString().toLowerCase().endsWith(".csv")) {
                throw new HarmoniaSuiteBadRequestException("Invalid selected CSV path: " + relative);
            }
            paths.add(candidate);
        }
        return paths.stream().sorted().toList();
    }

    private String toRelative(Path root, Path path) {
        return root.relativize(path.toAbsolutePath()).toString().replace('\\', '/');
    }

    private static List<UUID> uuidsOf(Map<String, String> fileIds, List<String> paths) {
        List<UUID> result = new ArrayList<>();
        if (paths == null) {
            return result;
        }
        for (String path : paths) {
            String id = fileIds.get(path);
            if (id != null) {
                result.add(ProjectRepository.uuidOf(id));
            }
        }
        return result;
    }

    private static String shortFingerprint(String fp) {
        return fp == null || fp.length() <= 12 ? String.valueOf(fp) : fp.substring(0, 12);
    }
}
