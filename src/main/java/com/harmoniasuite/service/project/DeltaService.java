package com.harmoniasuite.service.project;

import com.harmoniasuite.config.WorkspacePaths;
import com.harmoniasuite.domain.TranslationEntry;
import com.harmoniasuite.dto.DeltaConflictDto;
import com.harmoniasuite.dto.DeltaExportDto;
import com.harmoniasuite.dto.DeltaHeaderDto;
import com.harmoniasuite.dto.DeltaImportRequest;
import com.harmoniasuite.dto.DeltaImportResultDto;
import com.harmoniasuite.dto.DeltaRowDto;
import com.harmoniasuite.dto.DeltaSideDto;
import com.harmoniasuite.dto.DeltaSkippedDto;
import com.harmoniasuite.dto.DeltaWarningDto;
import com.harmoniasuite.dto.SummaryDto;
import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import com.harmoniasuite.mapping.EntryMapper;
import com.harmoniasuite.mapping.ProjectMapper;
import com.harmoniasuite.repository.EntryRepository;
import com.harmoniasuite.repository.PackRepository;
import com.harmoniasuite.repository.ProjectRepository;
import com.harmoniasuite.util.TagSupport;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DeltaService {

    public static final int DELTA_DEFAULT_LIMIT = 20000;
    public static final int DELTA_MAX_LIMIT = 20000;
    // Защита от абсурдных пейлоадов; импорт идёт одной транзакцией батчами — 100k рядовые.
    public static final int DELTA_MAX_ROWS = 100000;

    private static final List<String> WRITE_STATUSES =
            List.of("untranslated", "machine_translated", "no_translation_required", "needs_human_review", "approved");
    private static final Map<String, Integer> STATUS_RANK = Map.of(
            "untranslated", 0, "machine_translated", 1, "no_translation_required", 2,
            "needs_human_review", 3, "approved", 4);

    private final WorkspacePaths workspace;
    private final ProjectRepository projectRepository;
    private final EntryRepository entryRepository;
    private final PackRepository packRepository;
    private final ProjectMapper projectMapper;
    private final EntryMapper entryMapper;
    private final TransactionTemplate writeTx;

    public DeltaService(WorkspacePaths workspace, ProjectRepository projectRepository,
            EntryRepository entryRepository, PackRepository packRepository,
            ProjectMapper projectMapper, EntryMapper entryMapper,
            PlatformTransactionManager transactionManager) {
        this.workspace = workspace;
        this.projectRepository = projectRepository;
        this.entryRepository = entryRepository;
        this.packRepository = packRepository;
        this.projectMapper = projectMapper;
        this.entryMapper = entryMapper;
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    public DeltaExportDto exportDelta(UUID projectId, String sinceUpdatedAt, String sinceCellId,
            String files, int limit, String author) {
        projectRepository.findById(projectId);
        if (author == null || author.isBlank()) {
            throw new HarmoniaSuiteBadRequestException("Delta author is required");
        }
        int take = limit <= 0 ? DELTA_DEFAULT_LIMIT : Math.min(limit, DELTA_MAX_LIMIT);
        List<TranslationEntry> page = entryRepository.deltaPage(projectId,
                sinceUpdatedAt, sinceCellId, parseFiles(files), take);
        List<DeltaRowDto> rows = entryMapper.toDeltaRowDtoList(page);
        String nextUpdatedAt = sinceUpdatedAt == null ? "" : sinceUpdatedAt;
        String nextCellId = sinceCellId == null ? "" : sinceCellId;
        if (!page.isEmpty()) {
            TranslationEntry last = page.get(page.size() - 1);
            nextUpdatedAt = orEmpty(last.getUpdatedAt());
            nextCellId = orEmpty(last.getId());
        }
        String now = Instant.now().toString();
        DeltaHeaderDto header = new DeltaHeaderDto(projectRepository.sourcesFp(projectId),
                packRepository.gameVersion(projectId), author.trim(), now);
        return new DeltaExportDto(header, rows, nextUpdatedAt, nextCellId, page.size() < take);
    }

    public DeltaImportResultDto preview(UUID projectId, DeltaImportRequest request) {
        return importDelta(projectId, request, true);
    }

    public DeltaImportResultDto importDelta(UUID projectId, DeltaImportRequest request) {
        return importDelta(projectId, request, false);
    }

    private DeltaImportResultDto importDelta(UUID projectId, DeltaImportRequest request, boolean dryRun) {
        ProjectRepository.ProjectRow row = projectRepository.findById(projectId);
        if (request == null || request.rows() == null) {
            throw new HarmoniaSuiteBadRequestException("Delta rows are required");
        }
        if (request.rows().size() > DELTA_MAX_ROWS) {
            throw new HarmoniaSuiteBadRequestException(
                    "Too many delta rows: " + request.rows().size() + " (max " + DELTA_MAX_ROWS + ")");
        }
        String author = request.author() == null ? "" : request.author().trim();
        if (author.isEmpty()) {
            throw new HarmoniaSuiteBadRequestException("Delta author is required");
        }
        if (author.length() > 64) {
            throw new HarmoniaSuiteBadRequestException("Delta author is too long");
        }
        String maxStatus = request.maxStatus() == null || request.maxStatus().isBlank()
                ? "needs_human_review"
                : request.maxStatus().trim().toLowerCase(Locale.ROOT);
        if (!WRITE_STATUSES.contains(maxStatus)) {
            throw new HarmoniaSuiteBadRequestException("Unknown status: " + request.maxStatus());
        }
        String expectedFp = request.sourcesFp() == null ? "" : request.sourcesFp();
        String actualFp = projectRepository.sourcesFp(projectId);
        if (!expectedFp.equals(actualFp)) {
            throw new HarmoniaSuiteBadRequestException(
                    "sources_fp mismatch: delta is from another source snapshot");
        }
        List<String> allowlist = parseFilesList(request.filesAllowlist());
        Map<String, DeltaRowDto> incoming = new LinkedHashMap<>();
        List<DeltaSkippedDto> skipped = new ArrayList<>();
        for (DeltaRowDto item : request.rows()) {
            if (item == null || item.cellId() == null || item.cellId().isBlank()) {
                skipped.add(new DeltaSkippedDto(
                        item == null ? null : item.cellId(),
                        item == null ? null : item.filePath(), "bad_cell_id", null));
                continue;
            }
            incoming.put(item.cellId(), item);
        }
        Map<String, TranslationEntry> own = entryRepository.findByCells(projectId, List.copyOf(incoming.keySet()));
        List<EntryRepository.TranslationWrite> writes = new ArrayList<>();
        List<DeltaConflictDto> conflicts = new ArrayList<>();
        List<DeltaWarningDto> warnings = new ArrayList<>();
        int noop = 0;
        for (DeltaRowDto item : incoming.values()) {
            String file = item.filePath() == null ? "" : item.filePath().replace('\\', '/');
            if (allowlist != null && !allowlist.contains(file)) {
                skipped.add(new DeltaSkippedDto(item.cellId(), file, "not_assigned", null));
                continue;
            }
            TranslationEntry current = own.get(item.cellId());
            if (current == null) {
                skipped.add(new DeltaSkippedDto(item.cellId(), file, "unknown_cell", null));
                continue;
            }
            String status = item.status() == null ? "" : item.status().trim().toLowerCase(Locale.ROOT);
            if (!status.equals("stale") && !WRITE_STATUSES.contains(status)) {
                skipped.add(new DeltaSkippedDto(item.cellId(), file, "bad_status", item.status()));
                continue;
            }
            if (!status.equals("stale") && STATUS_RANK.get(status) > STATUS_RANK.get(maxStatus)) {
                skipped.add(new DeltaSkippedDto(item.cellId(), file, "status_above_cap",
                        status + " > " + maxStatus));
                continue;
            }
            String source = orEmpty(item.source());
            if (!orEmpty(current.getSource()).equals(source)) {
                skipped.add(new DeltaSkippedDto(item.cellId(), file, "source_mismatch",
                        orEmpty(current.getSource())));
                continue;
            }
            String translation = orEmpty(item.translation());
            // Теги не блокируют влитие: machine_translated часто их ломает, это ок —
            // расхождения видны в warnings, люди чинят вручную.
            if (orEmpty(current.getTranslation()).equals(translation)
                    && orEmpty(current.getStatus()).equals(status)) {
                noop++;
                continue;
            }
            String ownStatus = orEmpty(current.getStatus());
            if (ownStatus.equals("needs_human_review") || ownStatus.equals("approved")
                    || ownStatus.equals("no_translation_required")) {
                conflicts.add(new DeltaConflictDto(item.cellId(), file,
                        new DeltaSideDto(orEmpty(current.getTranslation()), ownStatus),
                        new DeltaSideDto(translation, status)));
                continue;
            }
            List<String> tagWarnings = TagSupport.warnings(current.getSource(), translation);
            if (!tagWarnings.isEmpty()) {
                warnings.add(new DeltaWarningDto(item.cellId(), file, tagWarnings));
            }
            writes.add(new EntryRepository.TranslationWrite(current.getUuid(),
                    orEmpty(current.getTranslation()), orEmpty(current.getStatus()),
                    translation, status));
        }
        String now = Instant.now().toString();
        if (!dryRun && !writes.isEmpty()) {
            String origin = "import:" + author;
            writeTx.execute(tx -> {
                entryRepository.batchWriteTranslations(projectId, writes, origin, now);
                return null;
            });
        }
        return new DeltaImportResultDto(writes.size(), noop, skipped, conflicts, warnings,
                summaryOf(projectId, row.outputDir()));
    }

    private SummaryDto summaryOf(UUID projectId, String outputDir) {
        ProjectRepository.ProjectSummary summary = projectRepository.summarize(projectId);
        return projectMapper.toSummaryDto(summary, summary.entries() - summary.translated(),
                relativizeIfPossible(outputDir));
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

    private static List<String> parseFiles(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<String> files = new ArrayList<>();
        for (String part : raw.split(",")) {
            String file = part.trim().replace('\\', '/');
            if (!file.isEmpty() && !files.contains(file)) {
                files.add(file);
            }
        }
        return files.isEmpty() ? null : files;
    }

    private static List<String> parseFilesList(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        List<String> files = new ArrayList<>();
        for (String part : raw) {
            if (part == null) {
                continue;
            }
            String file = part.trim().replace('\\', '/');
            if (!file.isEmpty() && !files.contains(file)) {
                files.add(file);
            }
        }
        return files.isEmpty() ? null : files;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
