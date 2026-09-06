package com.harmoniasuite.service;

import com.harmoniasuite.config.WorkspacePaths;
import com.harmoniasuite.domain.TranslationEntry;
import com.harmoniasuite.dto.CreateProjectResponseDto;
import com.harmoniasuite.dto.EntriesPageDto;
import com.harmoniasuite.dto.EntryDto;
import com.harmoniasuite.dto.FileStatsDto;
import com.harmoniasuite.dto.FileTreeDto;
import com.harmoniasuite.dto.OverviewDto;
import com.harmoniasuite.dto.ProjectFilesDto;
import com.harmoniasuite.dto.ProjectListDto;
import com.harmoniasuite.dto.SaveEntryResponseDto;
import com.harmoniasuite.dto.SummaryDto;
import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import com.harmoniasuite.exception.HarmoniaSuiteNotFoundException;
import com.harmoniasuite.mapping.EntryMapper;
import com.harmoniasuite.mapping.ProjectMapper;
import com.harmoniasuite.repository.EntryRepository;
import com.harmoniasuite.repository.ProjectRepository;
import com.harmoniasuite.util.TagSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

    public static final int ENTRIES_DEFAULT_LIMIT = 80;
    public static final int ENTRIES_MAX_LIMIT = 500;
    public static final int FILE_ENTRIES_MAX_LIMIT = 5000;
    public static final int FILES_DEFAULT_LIMIT = 100;
    public static final int FILES_MAX_LIMIT = 500;

    private static final List<String> ENTRY_STATUSES =
            List.of("untranslated", "machine_translated", "no_translation_required", "stale", "needs_human_review", "approved");
    private static final List<String> ENTRY_STATUS_FILTER =
            List.of("untranslated", "machine_translated", "no_translation_required", "stale", "needs_human_review",
                    "approved", "proofread");

    private static final int DELETE_BATCH = 20_000;

    private final WorkspacePaths workspace;
    private final ProjectRepository projectRepository;
    private final EntryRepository entryRepository;
    private final EntryMapper entryMapper;
    private final ProjectMapper projectMapper;

    public ProjectService(WorkspacePaths workspace, ProjectRepository projectRepository,
            EntryRepository entryRepository, EntryMapper entryMapper, ProjectMapper projectMapper) {
        this.workspace = workspace;
        this.projectRepository = projectRepository;
        this.entryRepository = entryRepository;
        this.entryMapper = entryMapper;
        this.projectMapper = projectMapper;
    }

    public List<ProjectListDto> listProjects() {
        Map<String, ProjectRepository.ProjectSummary> summaries = projectRepository.summaries();
        List<ProjectListDto> result = new ArrayList<>();
        for (ProjectRepository.ProjectRow row : projectRepository.listAll()) {
            ProjectRepository.ProjectSummary summary = summaries.getOrDefault(row.id(),
                    new ProjectRepository.ProjectSummary(0, 0, 0, Map.of()));
            try {
                result.add(projectMapper.toListDto(row, summary));
            } catch (Exception e) {
                result.add(projectMapper.toListDto(row, e.getMessage()));
            }
        }
        result.sort(Comparator.comparing(ProjectListDto::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public CreateProjectResponseDto createProject(String name, String inputRoot) throws IOException {
        name = sanitizeName(name);
        Path dir = workspace.resolve("projects").resolve(name);
        Files.createDirectories(dir);
        if (projectRepository.existsByName(name)) {
            throw new HarmoniaSuiteBadRequestException("Project already exists: " + name);
        }
        String now = Instant.now().toString();
        UUID projectId = projectRepository.insert(name,
                inputRoot == null || inputRoot.isBlank() ? "rawexd/en" : inputRoot,
                dir.toAbsolutePath().normalize().toString(),
                dir.resolve("exported_csv").toAbsolutePath().normalize().toString(),
                "en", "ru", now);
        return new CreateProjectResponseDto(projectId.toString(), name);
    }

    private SummaryDto summaryOf(UUID projectId, String outputDir) {
        return toSummaryDto(projectRepository.summarize(projectId), outputDir);
    }

    private SummaryDto toSummaryDto(ProjectRepository.ProjectSummary summary, String outputDir) {
        return projectMapper.toSummaryDto(summary, summary.entries() - summary.translated(),
                relativizeIfPossible(outputDir));
    }

    public OverviewDto overview(UUID projectId) {
        ProjectRepository.ProjectRow row = projectRepository.findById(projectId);
        return projectMapper.toOverviewDto(row, summaryOf(projectId, row.outputDir()),
                toWorkspaceRelative(row.inputRoot()),
                toWorkspaceRelative(row.outputDir()));
    }

    public FileTreeDto fileTree(UUID projectId) {
        projectRepository.findById(projectId);
        List<FileStatsDto> files = new ArrayList<>();
        for (ProjectRepository.FileWithStats file : projectRepository.fileTree(projectId)) {
            files.add(projectMapper.toFileStatsDto(file));
        }
        return new FileTreeDto(files, files.size());
    }

    public ProjectFilesDto listFiles(UUID projectId, String query, boolean hideReady,
            int offset, int limit) {
        return listFiles(projectId, query, hideReady, false, offset, limit);
    }

    public ProjectFilesDto listFiles(UUID projectId, String query, boolean hideReady, boolean readyOnly,
            int offset, int limit) {
        ProjectRepository.ProjectRow row = projectRepository.findById(projectId);
        int take = limit <= 0 ? FILES_DEFAULT_LIMIT : Math.min(limit, FILES_MAX_LIMIT);
        int from = Math.max(0, offset);
        ProjectRepository.FileStatsPage page =
                projectRepository.filesWithStats(projectId, query, hideReady, readyOnly, from, take);
        List<FileStatsDto> stats = new ArrayList<>();
        for (ProjectRepository.FileWithStats file : page.files()) {
            stats.add(projectMapper.toFileStatsDto(file));
        }
        return new ProjectFilesDto(stats, page.total(), from, take,
                page.needFiles(), page.readyFiles(), toSummaryDto(page.summary(), row.outputDir()));
    }

    public Map<String, Long> pendingTranslateByFile(UUID projectId) {
        projectRepository.findById(projectId);
        return entryRepository.pendingByFile(projectId);
    }

    public EntriesPageDto entries(UUID projectId, EntryRepository.EntryFilter filter,
            int offset, int limit) {
        ProjectRepository.ProjectRow row = projectRepository.findById(projectId);
        boolean fileScope = filter != null && filter.file() != null && !filter.file().isBlank();
        int cap = fileScope ? FILE_ENTRIES_MAX_LIMIT : ENTRIES_MAX_LIMIT;
        int take = limit <= 0 ? ENTRIES_DEFAULT_LIMIT : Math.min(limit, cap);
        int from = Math.max(0, offset);
        EntryRepository.EntryPage result = entryRepository.pageWithTotal(projectId, filter, from, take);
        return new EntriesPageDto(
                entryMapper.toDtoList(result.entries()), result.total(), from, take);
    }

    public static List<String> normalizeStatuses(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> statuses = new ArrayList<>();
        for (String part : raw.split(",")) {
            String status = part.trim().toLowerCase(Locale.ROOT);
            if (status.isEmpty()) {
                continue;
            }
            if (!ENTRY_STATUS_FILTER.contains(status)) {
                throw new HarmoniaSuiteBadRequestException("Unknown status: " + part.trim());
            }
            if (!statuses.contains(status)) {
                statuses.add(status);
            }
        }
        return statuses;
    }

    private String toWorkspaceRelative(String pathValue) {
        if (pathValue == null || pathValue.isBlank()) {
            return pathValue;
        }
        return relativizeIfPossible(pathValue);
    }

    private String relativizeIfPossible(String pathValue) {
        if (pathValue == null || pathValue.isBlank()) {
            return pathValue;
        }
        try {
            Path path = Path.of(pathValue).toAbsolutePath().normalize();
            Path root = workspace.root();
            if (path.startsWith(root)) {
                return root.relativize(path).toString().replace('\\', '/');
            }
        } catch (Exception ignored) {
            // keep original
        }
        return pathValue.replace('\\', '/');
    }

    public void deleteProject(UUID projectId, Consumer<String> log)
            throws IOException, InterruptedException {
        ProjectRepository.ProjectRow row = projectRepository.findById(projectId);
        // Без @Transactional: каждый батч коммитится сам, блокировка записи
        // отпускается между батчами — параллельные писатели не упираются в SQLITE_BUSY.
        long total = entryRepository.count(projectId, null);
        log.accept("  Записей к удалению: " + total);
        long removed = 0;
        int batch;
        do {
            if (Thread.currentThread().isInterrupted()) {
                log.accept("  Отменено: удалено " + removed + " из " + total);
                throw new InterruptedException("Удаление проекта отменено");
            }
            batch = entryRepository.deleteEntriesBatch(projectId, DELETE_BATCH);
            removed += batch;
            if (batch > 0) {
                log.accept("  Удалено записей: " + removed + " / " + total);
            }
        } while (batch > 0);
        if (projectRepository.delete(projectId) == 0) {
            throw new HarmoniaSuiteNotFoundException("Проект уже удалён");
        }
        Path dir = Path.of(row.projectDir());
        if (Files.isDirectory(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
        }
    }

    private static String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new HarmoniaSuiteBadRequestException("Project name is required");
        }
        String trimmed = name.trim().replace('\\', '/');
        if (trimmed.contains("/") || trimmed.contains("..")) {
            throw new HarmoniaSuiteBadRequestException("Invalid project name");
        }
        if (!trimmed.matches("[A-Za-z0-9._-]+")) {
            throw new HarmoniaSuiteBadRequestException("Project name may contain only letters, digits, . _ -");
        }
        return trimmed;
    }

    public EntryDto entry(UUID projectId, UUID entryId) {
        TranslationEntry entry = entryRepository.findByUuid(projectId, entryId);
        if (entry == null) {
            throw new HarmoniaSuiteNotFoundException("Entry not found: " + entryId);
        }
        return entryMapper.toDto(entry);
    }

    public EntryDto entryByCell(UUID projectId, String cellId) {
        projectRepository.findById(projectId);
        TranslationEntry entry = entryRepository.findByCell(projectId, cellId);
        if (entry == null) {
            throw new HarmoniaSuiteNotFoundException("Entry not found: " + cellId);
        }
        return entryMapper.toDto(entry);
    }

    @Transactional
    public SaveEntryResponseDto updateEntry(UUID projectId, UUID entryId, String translation, String status) {
        ProjectRepository.ProjectRow row = projectRepository.findById(projectId);
        TranslationEntry current = entryRepository.findByUuid(projectId, entryId);
        if (current == null) {
            throw new HarmoniaSuiteNotFoundException("Entry not found: " + entryId);
        }
        String nextTranslation = translation == null ? "" : translation;
        List<String> tagErrors = TagSupport.validate(nextTranslation);
        if (!tagErrors.isEmpty()) {
            throw new HarmoniaSuiteBadRequestException("Битые теги в переводе: " + String.join("; ", tagErrors));
        }
        List<String> tagWarnings = TagSupport.warnings(current.getSource(), nextTranslation);
        String nextStatus = status == null || status.isBlank()
                ? "needs_human_review" : status.trim().toLowerCase(Locale.ROOT);
        if (!ENTRY_STATUSES.contains(nextStatus)) {
            throw new HarmoniaSuiteBadRequestException("Unknown status: " + status);
        }
        String now = Instant.now().toString();
        int updated = entryRepository.updateTranslation(
                current.getUuid(), nextTranslation, nextStatus, now);
        if (updated == 0) {
            throw new HarmoniaSuiteNotFoundException("Entry not found: " + entryId);
        }
        entryRepository.insertHistory(projectId, current.getUuid(),
                current.getTranslation() == null ? "" : current.getTranslation(),
                current.getStatus() == null ? "" : current.getStatus(),
                nextTranslation, nextStatus, "editor", now);
        current.setTranslation(nextTranslation);
        current.setStatus(nextStatus);
        current.setUpdatedAt(now);
        return new SaveEntryResponseDto(true, entryMapper.toDto(current), tagWarnings,
                summaryOf(projectId, row.outputDir()));
    }
}
