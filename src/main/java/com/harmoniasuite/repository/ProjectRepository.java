package com.harmoniasuite.repository;

import com.harmoniasuite.exception.HarmoniaSuiteNotFoundException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectRepository {

    private static final String PROJECT_COLUMNS = """
            id, name, input_root, project_dir, output_dir, source_locale, target_locale,
            created_at, updated_at
            """;

    private static final String INSERT_PROJECT = """
            INSERT INTO projects (
                name, input_root, project_dir, output_dir, source_locale, target_locale,
                created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id""";

    private static final String SELECT_PROJECT_BY_ID = "SELECT " + PROJECT_COLUMNS
            + "FROM projects WHERE id = ?";

    private static final String SELECT_PROJECT_BY_NAME = "SELECT " + PROJECT_COLUMNS
            + "FROM projects WHERE name = ?";

    private static final String SELECT_ALL_PROJECTS = "SELECT " + PROJECT_COLUMNS
            + "FROM projects ORDER BY name";

    private static final String COUNT_PROJECTS_BY_NAME = """
            SELECT COUNT(*)
            FROM projects
            WHERE name = ?""";

    private static final String SELECT_PROJECT_EXISTS = """
            SELECT 1
            FROM projects
            WHERE id = ?""";

    private static final String SUMMARY_BY_STATUS = """
            SELECT s.status, s.entries_count AS n, s.translated_count AS translated,
                   (SELECT COUNT(*) FROM source_files sf WHERE sf.project_id = p.id) AS files
            FROM projects p
            LEFT JOIN entry_stats s ON s.project_id = p.id
            WHERE p.id = ?""";

    private static final String SUMMARY_WITH_PROJECT = """
            SELECT p.output_dir AS output_dir, s.status AS status,
                   COALESCE(s.entries_count, 0) AS n,
                   COALESCE(s.translated_count, 0) AS translated,
                   (SELECT COUNT(*) FROM source_files sf WHERE sf.project_id = p.id) AS files
            FROM projects p
            LEFT JOIN entry_stats s ON s.project_id = p.id
            WHERE p.id = ?
            """;

    private static final String SUMMARIES_ALL = """
            SELECT project_id, status, entries_count AS n, translated_count AS translated
            FROM entry_stats""";

    private static final String FILES_COUNT_BY_PROJECT = """
            SELECT project_id, COUNT(*) AS n FROM source_files GROUP BY project_id""";

    private static final String UPDATE_PROJECT_META = """
            UPDATE projects SET
                input_root = ?, project_dir = ?, output_dir = ?,
                source_locale = ?, target_locale = ?, created_at = ?, updated_at = ?
            WHERE id = ?""";

    private static final String SELECT_SOURCES_FP = """
            SELECT sources_fp FROM projects WHERE id = ?""";

    private static final String UPDATE_SOURCES_FP = """
            UPDATE projects SET
                sources_fp = ?, updated_at = ?
            WHERE id = ?""";

    private static final String DELETE_PROJECT = "DELETE FROM projects WHERE id = ?";

    private static final String DELETE_FILES = "DELETE FROM source_files";

    private static final String INSERT_FILE = """
            INSERT INTO source_files (
                project_id, path, created_at, updated_at
            )
            VALUES (?, ?, ?, ?)
            ON CONFLICT (project_id, path) DO UPDATE SET updated_at = excluded.updated_at""";

    private static final String SELECT_FILE_IDS = """
            SELECT id, path FROM source_files WHERE project_id = ?""";

    private static final String SELECT_FILE_ID = """
            SELECT id FROM source_files WHERE project_id = ? AND path = ?""";

    private static final String FILES_LIVE_BREAKDOWN = """
            SELECT f.path AS path, e.status AS status,
                   COUNT(e.id) AS n,
                   COALESCE(SUM(e.translated_flag), 0) AS tr
            FROM source_files f
            LEFT JOIN entries e ON e.file_id = f.id AND e.project_id = f.project_id
            WHERE f.project_id = ?
            GROUP BY f.id, f.path, e.status""";

    private static final String SELECT_FILES = """
            SELECT id, project_id, path
            FROM source_files WHERE project_id = ? ORDER BY path""";

    private static final String SELECT_FILE_FPS = """
            SELECT path, content_size, content_hash FROM source_files WHERE project_id = ?""";

    private static final String UPDATE_FILE_FP = """
            UPDATE source_files SET
                content_size = ?, content_hash = ?, updated_at = ?
            WHERE project_id = ? AND path = ?""";

    private static final RowMapper<ProjectRow> ROW_MAPPER = (rs, i) -> new ProjectRow(
            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
            rs.getString(9));

    private static final RowMapper<FileRow> FILE_MAPPER = (rs, i) -> new FileRow(
            rs.getString(1), rs.getString(2), rs.getString(3));

    private static final RowMapper<ProjectSummaryRow> SUMMARY_ROW_MAPPER = (rs, i) ->
            new ProjectSummaryRow(rs.getString("output_dir"), rs.getString("status"),
                    rs.getLong("n"), rs.getLong("translated"), rs.getLong("files"));

    private final JdbcTemplate jdbc;

    public ProjectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ProjectRow(
            String id,
            String name,
            String inputRoot,
            String projectDir,
            String outputDir,
            String sourceLocale,
            String targetLocale,
            String createdAt,
            String updatedAt) {
    }

    public record FileRow(
            String id,
            String projectId,
            String path) {
    }

    public record FileFingerprint(
            long size,
            String hash) {
    }

    public record ProjectSummary(
            long files,
            long entries,
            long translated,
            Map<String, Long> byStatus) {
    }

    public record ProjectSnapshot(ProjectSummary summary, String outputDir) {
    }

    private record ProjectSummaryRow(
            String outputDir,
            String status,
            long entries,
            long translated,
            long files) {
    }

    public static java.util.UUID uuidOf(String value) {
        return java.util.UUID.fromString(value);
    }

    public boolean existsByName(String name) {
        Integer total = jdbc.queryForObject(COUNT_PROJECTS_BY_NAME, Integer.class, name);
        return total != null && total > 0;
    }

    public UUID insert(String name, String inputRoot, String projectDir, String outputDir,
            String sourceLocale, String targetLocale, String now) {
        String id = jdbc.queryForObject(INSERT_PROJECT, (rs, i) -> rs.getString(1),
                name, inputRoot, projectDir, outputDir, sourceLocale, targetLocale, now, now);
        UUID project = UUID.fromString(id);
        return project;
    }

    public ProjectRow findById(UUID projectId) {
        List<ProjectRow> rows = jdbc.query(SELECT_PROJECT_BY_ID, ROW_MAPPER, projectId);
        if (rows.isEmpty()) {
            throw new HarmoniaSuiteNotFoundException("Project not found: " + projectId);
        }
        return rows.get(0);
    }

    public boolean exists(UUID projectId) {
        return !jdbc.query(SELECT_PROJECT_EXISTS, (rs, rowNum) -> Boolean.TRUE, projectId).isEmpty();
    }

    public ProjectRow findByName(String name) {
        List<ProjectRow> rows = jdbc.query(SELECT_PROJECT_BY_NAME, ROW_MAPPER, name);
        if (rows.isEmpty()) {
            throw new HarmoniaSuiteNotFoundException("Project not found: " + name);
        }
        return rows.get(0);
    }

    public List<ProjectRow> listAll() {
        return jdbc.query(SELECT_ALL_PROJECTS, ROW_MAPPER);
    }

    public ProjectSummary summarize(UUID projectId) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        long entries = 0;
        long translated = 0;
        long files = 0;
        for (Map<String, Object> row : jdbc.queryForList(SUMMARY_BY_STATUS, projectId)) {
            files = ((Number) row.get("files")).longValue();
            if (row.get("status") == null) {
                continue;
            }
            long n = ((Number) row.get("n")).longValue();
            byStatus.put((String) row.get("status"), n);
            entries += n;
            translated += ((Number) row.get("translated")).longValue();
        }
        return new ProjectSummary(files, entries, translated, byStatus);
    }

    public ProjectSnapshot summarizeWithProject(UUID projectId) {
        List<ProjectSummaryRow> rows = jdbc.query(
                SUMMARY_WITH_PROJECT, SUMMARY_ROW_MAPPER, projectId);
        if (rows.isEmpty()) {
            throw new HarmoniaSuiteNotFoundException("Project not found: " + projectId);
        }
        ProjectSummaryRow first = rows.getFirst();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        long entries = 0;
        long translated = 0;
        for (ProjectSummaryRow row : rows) {
            if (row.status() == null) {
                continue;
            }
            byStatus.put(row.status(), row.entries());
            entries += row.entries();
            translated += row.translated();
        }
        return new ProjectSnapshot(
                new ProjectSummary(first.files(), entries, translated, byStatus), first.outputDir());
    }

    public Map<String, ProjectSummary> summaries() {
        Map<String, Long> files = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(FILES_COUNT_BY_PROJECT)) {
            files.put((String) row.get("project_id"), ((Number) row.get("n")).longValue());
        }
        Map<String, long[]> counters = new LinkedHashMap<>();
        Map<String, Map<String, Long>> byStatus = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(SUMMARIES_ALL)) {
            String pid = (String) row.get("project_id");
            long n = ((Number) row.get("n")).longValue();
            long[] pair = counters.computeIfAbsent(pid, k -> new long[2]);
            pair[0] += n;
            pair[1] += ((Number) row.get("translated")).longValue();
            byStatus.computeIfAbsent(pid, k -> new LinkedHashMap<>())
                    .put((String) row.get("status"), n);
        }
        Map<String, ProjectSummary> result = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> entry : counters.entrySet()) {
            String pid = entry.getKey();
            long[] pair = entry.getValue();
            result.put(pid, new ProjectSummary(files.getOrDefault(pid, 0L), pair[0], pair[1],
                    byStatus.getOrDefault(pid, Map.of())));
        }
        for (Map.Entry<String, Long> entry : files.entrySet()) {
            result.putIfAbsent(entry.getKey(),
                    new ProjectSummary(entry.getValue(), 0, 0, Map.of()));
        }
        return result;
    }

    public void updateMeta(UUID id, String inputRoot, String projectDir, String outputDir,
            String sourceLocale, String targetLocale, String createdAt, String updatedAt) {
        jdbc.update(UPDATE_PROJECT_META,
                inputRoot, projectDir, outputDir, sourceLocale, targetLocale,
                createdAt, updatedAt, id);
    }

    public String sourcesFingerprint(UUID projectId) {
        String fingerprint = jdbc.queryForObject(SELECT_SOURCES_FP, String.class, projectId);
        return fingerprint == null ? "" : fingerprint;
    }

    public void updateSourcesFingerprint(UUID projectId, String fingerprint, String updatedAt) {
        jdbc.update(UPDATE_SOURCES_FP, fingerprint == null ? "" : fingerprint, updatedAt, projectId);
    }

    public Map<String, FileFingerprint> fileFingerprints(UUID projectId) {
        Map<String, FileFingerprint> result = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(SELECT_FILE_FPS, projectId)) {
            Object size = row.get("content_size");
            Object hash = row.get("content_hash");
            result.put((String) row.get("path"), new FileFingerprint(
                    size instanceof Number number ? number.longValue() : -1,
                    hash instanceof String text ? text : ""));
        }
        return result;
    }

    public void updateFileFingerprints(UUID projectId, Map<String, FileFingerprint> fingerprints, String now) {
        if (fingerprints == null || fingerprints.isEmpty()) {
            return;
        }
        List<Object[]> batch = new ArrayList<>(fingerprints.size());
        for (Map.Entry<String, FileFingerprint> item : fingerprints.entrySet()) {
            batch.add(new Object[]{item.getValue().size(), item.getValue().hash(), now, projectId, item.getKey()});
        }
        jdbc.batchUpdate(UPDATE_FILE_FP, batch);
    }

    public void deleteFilesByPaths(UUID projectId, List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        SqlBuilder filter = SqlBuilder.where("project_id = ?", projectId).andIn("path", paths);
        jdbc.update(DELETE_FILES + filter.text(), filter.params());
    }

    public int delete(UUID id) {
        return jdbc.update(DELETE_PROJECT, id);
    }

    public void upsertFiles(UUID projectId, List<String> paths, String now) {
        List<Object[]> batch = new ArrayList<>(paths.size());
        for (String path : paths) {
            batch.add(new Object[]{projectId, path, now, now});
        }
        if (batch.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(INSERT_FILE, batch);
    }

    public void deleteStaleFiles(UUID projectId, String stamp) {
        SqlBuilder filter = SqlBuilder.deleteFrom("source_files")
                .and("project_id = ?", projectId)
                .and("updated_at <> ?", stamp);
        jdbc.update(filter.text(), filter.params());
    }

    public Map<String, String> fileIdMap(UUID projectId) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(SELECT_FILE_IDS, projectId)) {
            result.put((String) row.get("path"), String.valueOf(row.get("id")));
        }
        return result;
    }

    public List<FileRow> files(UUID projectId) {
        return jdbc.query(SELECT_FILES, FILE_MAPPER, projectId);
    }

    public record FileWithStats(
            String path,
            long total,
            long done) {
    }

    public record FileStatsPage(
            List<FileWithStats> files,
            long total,
            long needFiles,
            long readyFiles,
            ProjectSummary summary) {
    }

    private record FileBreakdown(Map<String, long[]> perFile, ProjectSummary summary) {
    }

    private FileBreakdown loadBreakdown(UUID projectId) {
        Map<String, long[]> perFile = new LinkedHashMap<>();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        long entries = 0;
        long translated = 0;
        for (Map<String, Object> row : jdbc.queryForList(FILES_LIVE_BREAKDOWN, projectId)) {
            String path = (String) row.get("path");
            String status = (String) row.get("status");
            long n = ((Number) row.get("n")).longValue();
            long tr = ((Number) row.get("tr")).longValue();
            long[] pair = perFile.computeIfAbsent(path, k -> new long[2]);
            pair[0] += n;
            pair[1] += tr;
            entries += n;
            translated += tr;
            if (status != null) {
                byStatus.merge(status, n, Long::sum);
            }
        }
        return new FileBreakdown(perFile,
                new ProjectSummary(perFile.size(), entries, translated, byStatus));
    }

    public List<FileWithStats> fileTree(UUID projectId) {
        List<FileWithStats> files = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : loadBreakdown(projectId).perFile().entrySet()) {
            files.add(new FileWithStats(entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
        }
        files.sort(Comparator.comparing(FileWithStats::path));
        return files;
    }

    public FileStatsPage filesWithStats(UUID projectId, String query, boolean hideReady, boolean readyOnly,
            int offset, int limit) {
        String needle = query == null || query.isBlank()
                ? null
                : query.trim().toLowerCase(Locale.ROOT);
        FileBreakdown breakdown = loadBreakdown(projectId);
        Map<String, long[]> perFile = breakdown.perFile();
        List<FileWithStats> filtered = new ArrayList<>();
        long need = 0;
        long ready = 0;
        for (Map.Entry<String, long[]> entry : perFile.entrySet()) {
            String path = entry.getKey();
            long total = entry.getValue()[0];
            long done = entry.getValue()[1];
            if (needle != null && !path.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            if (hideReady && total <= done) {
                continue;
            }
            if (readyOnly && done <= 0) {
                continue;
            }
            filtered.add(new FileWithStats(path, total, done));
            if (total > done) {
                need++;
            } else if (total > 0) {
                ready++;
            }
        }
        filtered.sort(Comparator.comparingLong((FileWithStats f) -> f.total() - f.done())
                .reversed()
                .thenComparing(FileWithStats::path));
        int from = Math.max(0, offset);
        int to = Math.min(filtered.size(), from + Math.max(0, limit));
        List<FileWithStats> page =
                from >= filtered.size() ? List.of() : List.copyOf(filtered.subList(from, to));
        return new FileStatsPage(page, filtered.size(), need, ready, breakdown.summary());
    }

    public String fileId(UUID projectId, String path) {
        List<String> rows = jdbc.query(SELECT_FILE_ID,
                (rs, i) -> rs.getString(1), projectId, path);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
