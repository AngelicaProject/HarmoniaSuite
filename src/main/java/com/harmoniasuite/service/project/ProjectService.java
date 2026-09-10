package com.harmoniasuite.service.project;

import com.harmoniasuite.config.WorkspacePaths;
import com.harmoniasuite.domain.EntryStatusPolicy;
import com.harmoniasuite.dto.CreateProjectResponseDto;
import com.harmoniasuite.dto.EntriesPageDto;
import com.harmoniasuite.dto.EntryDto;
import com.harmoniasuite.dto.FileStatsDto;
import com.harmoniasuite.dto.FileTreeDto;
import com.harmoniasuite.dto.OverviewDto;
import com.harmoniasuite.dto.ProjectFilesDto;
import com.harmoniasuite.dto.ProjectListDto;
import com.harmoniasuite.dto.RowGroupsPageDto;
import com.harmoniasuite.dto.SaveEntryResponseDto;
import com.harmoniasuite.dto.SummaryDto;
import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import com.harmoniasuite.exception.HarmoniaSuiteNotFoundException;
import com.harmoniasuite.mapping.EntryMapper;
import com.harmoniasuite.mapping.ProjectMapper;
import com.harmoniasuite.repository.EntryRepository;
import com.harmoniasuite.repository.ProjectRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ProjectService {

    /** Compatibility aliases while callers migrate to EntryService constants. */
    public static final int ENTRIES_DEFAULT_LIMIT = EntryService.DEFAULT_LIMIT;
    public static final int ENTRIES_MAX_LIMIT = EntryService.MAX_LIMIT;
    public static final int FILE_ENTRIES_MAX_LIMIT = EntryService.FILE_MAX_LIMIT;
    public static final int ROW_GROUPS_DEFAULT_PAGE_SIZE = EntryService.ROW_GROUPS_DEFAULT_PAGE_SIZE;
    public static final int ROW_GROUPS_MAX_PAGE_SIZE = EntryService.ROW_GROUPS_MAX_PAGE_SIZE;
    public static final int FILES_DEFAULT_LIMIT = 100;
    public static final int FILES_MAX_LIMIT = 500;

    private static final int DELETE_BATCH = 20_000;

    private final WorkspacePaths workspace;
    private final ProjectRepository projectRepository;
    private final EntryRepository entryRepository;
    private final ProjectMapper projectMapper;
    private final EntryService entryService;

    /** Compatibility constructor for direct unit-test/application callers. */
    public ProjectService(WorkspacePaths workspace, ProjectRepository projectRepository,
            EntryRepository entryRepository, EntryMapper entryMapper, ProjectMapper projectMapper) {
        this(workspace, projectRepository, entryRepository, projectMapper,
                new EntryService(workspace, projectRepository, entryRepository, entryMapper, projectMapper));
    }

    @Autowired
    public ProjectService(WorkspacePaths workspace, ProjectRepository projectRepository,
            EntryRepository entryRepository, ProjectMapper projectMapper,
            EntryService entryService) {
        this.workspace = workspace;
        this.projectRepository = projectRepository;
        this.entryRepository = entryRepository;
        this.projectMapper = projectMapper;
        this.entryService = entryService;
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
        if (projectRepository.existsByName(name)) {
            throw new HarmoniaSuiteBadRequestException("Project already exists: " + name);
        }
        Path dir = workspace.resolve("projects").resolve(name);
        Files.createDirectories(dir);
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
                relativizeIfPossible(row.inputRoot()),
                relativizeIfPossible(row.outputDir()));
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

    public EntriesPageDto entries(UUID projectId, EntryRepository.EntryFilter filter,
            int offset, int limit) {
        EntryService.EntrySearch search = filter == null
                ? EntryService.EntrySearch.empty()
                : new EntryService.EntrySearch(filter.file(), filter.query(),
                        filter.statuses() == null ? "" : String.join(",", filter.statuses()),
                        filter.rowKey(), filter.onlyUntranslated(), offset, limit);
        return entryService.search(projectId, search);
    }

    public RowGroupsPageDto rowGroups(UUID projectId, String file, String query,
            int offset, int limit) {
        return entryService.rowGroups(projectId, file, query, offset, limit);
    }

    public long rowGroupPosition(UUID projectId, String file, int rowIndex, String query) {
        return entryService.rowGroupPosition(projectId, file, rowIndex, query);
    }

    public EntryDto nextNeedsWork(UUID projectId, String file, int afterRow, int afterCol, String query) {
        return entryService.nextNeedsWork(projectId, file, afterRow, afterCol, query);
    }

    public static List<String> normalizeStatuses(String raw) {
        return EntryStatusPolicy.parseFilter(raw);
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
        return entryService.find(projectId, entryId);
    }

    public EntryDto entryByCell(UUID projectId, String cellId) {
        return entryService.findByCell(projectId, cellId);
    }

    public SaveEntryResponseDto updateEntry(UUID projectId, UUID entryId, String translation, String status) {
        return entryService.update(projectId, entryId,
                new com.harmoniasuite.dto.UpdateEntryRequest(translation, status));
    }
}
