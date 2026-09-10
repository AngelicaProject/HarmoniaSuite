package com.harmoniasuite.service.project;

import com.harmoniasuite.config.WorkspacePaths;
import com.harmoniasuite.domain.EntryQuery;
import com.harmoniasuite.domain.EntryStatusPolicy;
import com.harmoniasuite.domain.TranslationEntry;
import com.harmoniasuite.dto.EntriesPageDto;
import com.harmoniasuite.dto.EntryDto;
import com.harmoniasuite.dto.RowGroupsPageDto;
import com.harmoniasuite.dto.SaveEntryResponseDto;
import com.harmoniasuite.dto.SummaryDto;
import com.harmoniasuite.dto.UpdateEntryRequest;
import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import com.harmoniasuite.exception.HarmoniaSuiteNotFoundException;
import com.harmoniasuite.mapping.EntryMapper;
import com.harmoniasuite.mapping.ProjectMapper;
import com.harmoniasuite.repository.EntryRepository;
import com.harmoniasuite.repository.ProjectRepository;
import com.harmoniasuite.util.TagSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Application boundary for entry reads and writes. */
@Service
public class EntryService {

    public static final int DEFAULT_LIMIT = 80;
    public static final int MAX_LIMIT = 500;
    public static final int FILE_MAX_LIMIT = 5000;
    public static final int ROW_GROUPS_DEFAULT_PAGE_SIZE = 60;
    public static final int ROW_GROUPS_MAX_PAGE_SIZE = 200;

    private final ProjectRepository projectRepository;
    private final EntryRepository entryRepository;
    private final EntryMapper entryMapper;
    private final ProjectMapper projectMapper;
    private final WorkspacePaths workspace;

    public EntryService(WorkspacePaths workspace, ProjectRepository projectRepository,
            EntryRepository entryRepository, EntryMapper entryMapper, ProjectMapper projectMapper) {
        this.workspace = workspace;
        this.projectRepository = projectRepository;
        this.entryRepository = entryRepository;
        this.entryMapper = entryMapper;
        this.projectMapper = projectMapper;
    }

    public EntriesPageDto search(UUID projectId, EntrySearch search) {
        EntrySearch value = search == null ? EntrySearch.empty() : search;
        EntryQuery query = toQuery(value);
        int take = pageSize(query.file(), value.limit());
        int from = Math.max(0, value.offset());
        EntryRepository.EntryPage page = entryRepository.pageWithTotal(projectId, query, from, take);
        if (page.total() == 0) {
            requireExistingProject(projectId);
        }
        return new EntriesPageDto(entryMapper.toDtoList(page.entries()), page.total(), from, take);
    }

    public EntryDto find(UUID projectId, UUID entryId) {
        TranslationEntry entry = entryRepository.findByUuid(projectId, entryId);
        if (entry == null) {
            throw new HarmoniaSuiteNotFoundException("Entry not found: " + entryId);
        }
        return entryMapper.toDto(entry);
    }

    public EntryDto findByCell(UUID projectId, String cellId) {
        TranslationEntry entry = entryRepository.findByCell(projectId, cellId);
        if (entry == null) {
            throw new HarmoniaSuiteNotFoundException("Entry not found: " + cellId);
        }
        return entryMapper.toDto(entry);
    }

    @Transactional
    public SaveEntryResponseDto update(UUID projectId, UUID entryId, UpdateEntryRequest request) {
        TranslationEntry current = entryRepository.findByUuid(projectId, entryId);
        if (current == null) {
            throw new HarmoniaSuiteNotFoundException("Entry not found: " + entryId);
        }

        String nextTranslation = request == null || request.translation() == null
                ? "" : request.translation();
        List<String> tagErrors = TagSupport.validate(nextTranslation);
        if (!tagErrors.isEmpty()) {
            throw new HarmoniaSuiteBadRequestException(
                    "Битые теги в переводе: " + String.join("; ", tagErrors));
        }

        String nextStatus = EntryStatusPolicy.forWrite(request == null ? null : request.status());
        List<String> warnings = TagSupport.warnings(current.getSource(), nextTranslation);
        String now = Instant.now().toString();
        UUID currentId = ProjectRepository.uuidOf(current.getUuid());
        int updated = entryRepository.updateTranslation(currentId, nextTranslation, nextStatus, now);
        if (updated == 0) {
            throw new HarmoniaSuiteNotFoundException("Entry not found: " + entryId);
        }
        entryRepository.insertHistory(projectId, currentId,
                orEmpty(current.getTranslation()), orEmpty(current.getStatus()),
                nextTranslation, nextStatus, "editor", now);

        current.setTranslation(nextTranslation);
        current.setStatus(nextStatus);
        current.setUpdatedAt(now);
        ProjectRepository.ProjectSnapshot snapshot = projectRepository.summarizeWithProject(projectId);
        ProjectRepository.ProjectSummary projectSummary = snapshot.summary();
        SummaryDto summary = projectMapper.toSummaryDto(projectSummary,
                projectSummary.entries() - projectSummary.translated(),
                relativizeIfPossible(snapshot.outputDir()));
        return new SaveEntryResponseDto(true, entryMapper.toDto(current), warnings, summary);
    }

    public RowGroupsPageDto rowGroups(UUID projectId, String file, String query,
            int offset, int limit) {
        String normalizedFile = requireFile(file);
        String normalizedQuery = nullIfBlank(query);
        int take = limit <= 0 ? ROW_GROUPS_DEFAULT_PAGE_SIZE
                : Math.min(limit, ROW_GROUPS_MAX_PAGE_SIZE);
        int from = Math.max(0, offset);
        List<TranslationEntry> entries = entryRepository.rowGroupWindow(
                projectId, normalizedFile, normalizedQuery, from, take);
        long total = entryRepository.countRowGroups(projectId, normalizedFile, normalizedQuery);
        if (total == 0) {
            requireExistingProject(projectId);
        }
        LinkedHashMap<Integer, RowGroupBuilder> grouped = new LinkedHashMap<>();
        for (TranslationEntry entry : entries) {
            RowGroupBuilder group = grouped.computeIfAbsent(entry.getRowIndex(),
                    row -> new RowGroupBuilder(row, entry.getRowKey()));
            group.cells.add(entryMapper.toDto(entry));
            if (needsWork(entry)) {
                group.needsWork++;
            }
        }
        List<RowGroupsPageDto.RowGroupDto> result = new ArrayList<>(grouped.size());
        for (RowGroupBuilder group : grouped.values()) {
            result.add(new RowGroupsPageDto.RowGroupDto(
                    group.row, group.rowKey, group.cells, group.needsWork));
        }
        return new RowGroupsPageDto(result,
                total, from, take);
    }

    public long rowGroupPosition(UUID projectId, String file, int rowIndex, String query) {
        long position = entryRepository.rowGroupPosition(projectId, requireFile(file),
                rowIndex, nullIfBlank(query));
        requireExistingProject(projectId);
        return position;
    }

    public EntryDto nextNeedsWork(UUID projectId, String file, int afterRow, int afterCol,
            String query) {
        TranslationEntry entry = entryRepository.nextNeedsWork(
                projectId, requireFile(file), afterRow, afterCol, nullIfBlank(query));
        if (entry == null) {
            requireExistingProject(projectId);
        }
        return entry == null ? null : entryMapper.toDto(entry);
    }

    public Map<String, Long> pendingByFile(UUID projectId) {
        Map<String, Long> pending = entryRepository.pendingByFile(projectId);
        if (pending.isEmpty()) {
            requireExistingProject(projectId);
        }
        return pending;
    }

    public static EntryQuery toQuery(EntrySearch search) {
        EntrySearch value = search == null ? EntrySearch.empty() : search;
        return new EntryQuery(nullIfBlank(value.rowKey()),
                EntryStatusPolicy.parseFilter(value.status()), nullIfBlankPath(value.file()),
                nullIfBlank(value.query()), value.onlyUntranslated());
    }

    private static int pageSize(String file, int requested) {
        int cap = file == null ? MAX_LIMIT : FILE_MAX_LIMIT;
        return requested <= 0 ? DEFAULT_LIMIT : Math.min(requested, cap);
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nullIfBlankPath(String value) {
        String normalized = nullIfBlank(value);
        return normalized == null ? null : normalized.replace('\\', '/');
    }

    private static String requireFile(String file) {
        String normalized = nullIfBlankPath(file);
        if (normalized == null) {
            throw new HarmoniaSuiteBadRequestException("File is required");
        }
        return normalized;
    }

    private static boolean needsWork(TranslationEntry entry) {
        return !"no_translation_required".equals(entry.getStatus())
                && ((entry.getTranslation() == null || entry.getTranslation().trim().isEmpty())
                || "stale".equals(entry.getStatus()));
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private void requireExistingProject(UUID projectId) {
        if (!projectRepository.exists(projectId)) {
            throw new HarmoniaSuiteNotFoundException("Project not found: " + projectId);
        }
    }

    private String relativizeIfPossible(String pathValue) {
        if (pathValue == null || pathValue.isBlank()) {
            return pathValue;
        }
        try {
            java.nio.file.Path path = java.nio.file.Path.of(pathValue).toAbsolutePath().normalize();
            java.nio.file.Path root = workspace.root();
            if (path.startsWith(root)) {
                return root.relativize(path).toString().replace('\\', '/');
            }
        } catch (Exception ignored) {
            // Keep an externally configured path unchanged.
        }
        return pathValue.replace('\\', '/');
    }

    public record EntrySearch(String file, String query, String status, String rowKey,
            boolean onlyUntranslated, int offset, int limit) {

        public static EntrySearch empty() {
            return new EntrySearch(null, null, null, null, false, 0, 0);
        }
    }

    private static final class RowGroupBuilder {
        private final int row;
        private final String rowKey;
        private final List<EntryDto> cells = new ArrayList<>();
        private int needsWork;

        private RowGroupBuilder(int row, String rowKey) {
            this.row = row;
            this.rowKey = rowKey;
        }
    }
}
