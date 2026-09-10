package com.harmoniasuite.repository;

import com.harmoniasuite.domain.EntryIds;
import com.harmoniasuite.domain.EntryQuery;
import com.harmoniasuite.domain.TranslationEntry;
import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class EntryRepository {

    private static final String ENTRY_COLUMNS = """
            id, cell_id, file_path, row_key, column_index, column_name, row_index,
            source, translation, status, created_at, updated_at
            """;

    private static final String SELECT_BASE = "SELECT " + ENTRY_COLUMNS + " FROM entries";

    private static final String COUNT_BASE = "SELECT COUNT(*) FROM entries";

    private static final String DELETE_ENTRIES_BATCH = """
            DELETE FROM entries WHERE id IN (
                SELECT id FROM entries WHERE project_id = ? LIMIT ?)""";

    private static final String SELECT_PAGE_WITH_TOTAL = "SELECT " + ENTRY_COLUMNS + """
            , COUNT(*) OVER () AS _total FROM entries""";

    private static final String SELECT_ROW_GROUPS = "SELECT DISTINCT row_index FROM entries";

    private static final String SELECT_ROW_GROUP_CELLS = SELECT_BASE;

    private static final String COUNT_ROW_GROUPS = "SELECT COUNT(DISTINCT row_index) FROM entries";

    private static final String NEEDS_WORK_PREDICATE =
            "status <> 'no_translation_required' AND (TRIM(translation) = '' OR status = 'stale')";

    private static final String SELECT_TRANSLATED_BY_FILE = SELECT_BASE
            + " WHERE project_id = ? AND file_id = ? AND TRIM(translation) <> ''"
            + " ORDER BY row_index, column_index";

    private static final String SELECT_TRANSLATED_CELLS = """
            SELECT cell_id, file_path, source, translation, status, column_name
            FROM entries WHERE project_id = ? AND TRIM(translation) <> ''""";

    private static final String SELECT_TRANSLATED_FILE_PATHS = """
            SELECT DISTINCT file_path AS file
            FROM entries
            WHERE project_id = ?
            AND TRIM(translation) <> ''
            AND status <> 'stale'""";

    private static final String SELECT_PENDING_BY_FILE = """
            SELECT file_path, COUNT(*) AS total
            FROM entries
            WHERE project_id = ?
            AND TRIM(translation) = ''
            AND status <> 'no_translation_required'
            GROUP BY file_path
            ORDER BY file_path""";

    private static final String UPDATE_TRANSLATION = """
            UPDATE entries SET
                translation = ?, translation_lc = ?, status = ?, updated_at = ?
            WHERE id = ?""";

    private static final String INSERT_HISTORY = """
            INSERT INTO entry_history (
                project_id, entry_id, old_translation, old_status,
                new_translation, new_status, origin, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String INSERT_ENTRY = """
            INSERT INTO entries (
                project_id, file_id, cell_id, file_path, row_key, column_index,
                column_name, row_index, source, source_lc, translation, translation_lc,
                status, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (project_id, cell_id) DO UPDATE SET
                file_id = excluded.file_id, file_path = excluded.file_path,
                row_key = excluded.row_key, column_index = excluded.column_index,
                column_name = excluded.column_name, row_index = excluded.row_index,
                source = excluded.source, source_lc = excluded.source_lc,
                translation = excluded.translation, translation_lc = excluded.translation_lc,
                status = excluded.status, updated_at = excluded.updated_at""";

    private static final String SELECT_PROGRESS = """
            SELECT file_path AS file, COUNT(*) AS total,
                   SUM(CASE WHEN TRIM(translation) <> '' AND status <> 'stale'
                       THEN 1 ELSE 0 END) AS done
            FROM entries WHERE project_id = ? GROUP BY file_path ORDER BY file_path""";

    private static final String ENTRY_ORDER = "file_path, row_index, column_index";

    private static final RowMapper<TranslationEntry> ROW_MAPPER = (rs, i) -> {
        TranslationEntry entry = new TranslationEntry();
        entry.setUuid(rs.getString("id"));
        entry.setId(rs.getString("cell_id"));
        entry.setFile(rs.getString("file_path"));
        entry.setRowKey(rs.getString("row_key"));
        entry.setColumnIndex(rs.getInt("column_index"));
        entry.setColumnName(rs.getString("column_name"));
        entry.setRowIndex(rs.getInt("row_index"));
        entry.setSource(rs.getString("source"));
        entry.setTranslation(rs.getString("translation"));
        entry.setStatus(rs.getString("status"));
        entry.setCreatedAt(rs.getString("created_at"));
        entry.setUpdatedAt(rs.getString("updated_at"));
        return entry;
    };

    private static final RowMapper<EntryPageRow> PAGE_ROW_MAPPER = (rs, i) ->
            new EntryPageRow(ROW_MAPPER.mapRow(rs, i), rs.getLong("_total"));

    private final JdbcTemplate jdbc;

    public EntryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record EntryFilter(
            String rowKey,
            List<String> statuses,
            String file,
            String query,
            boolean onlyUntranslated) {

        /** Transitional adapter for callers that still use the old repository API. */
        public EntryQuery toQuery() {
            return new EntryQuery(rowKey, statuses, file, query, onlyUntranslated);
        }
    }

    public static void validateCell(TranslationEntry entry) {
        if (entry.getId() == null || entry.getSource() == null) {
            throw new HarmoniaSuiteBadRequestException("Every entry requires string id and source");
        }
        String expected = EntryIds.ofCell(
                entry.getFile() == null ? "" : entry.getFile(),
                entry.getRowKey() == null ? "" : entry.getRowKey(),
                entry.getColumnIndex());
        if (!entry.getId().equals(expected)) {
            throw new HarmoniaSuiteBadRequestException("Entry id does not match cell: " + entry.getId());
        }
    }

    private static SqlBuilder whereClause(UUID projectId, EntryQuery filter) {
        SqlBuilder builder = SqlBuilder.where("project_id = ?", projectId);
        if (filter == null) {
            return builder;
        }
        if (filter.rowKey() != null && !filter.rowKey().isBlank()) {
            builder.and("LOWER(row_key) = ?", filter.rowKey().trim().toLowerCase(Locale.ROOT));
        }
        if (filter.statuses() != null && !filter.statuses().isEmpty()) {
            builder.andIn("status", filter.statuses().stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(s -> s.trim().toLowerCase(Locale.ROOT)).toList());
        }
        if (filter.file() != null && !filter.file().isBlank()) {
            builder.and("file_path = ?", filter.file().trim().replace('\\', '/'));
        }
        if (filter.onlyUntranslated()) {
            builder.and("TRIM(translation) = ''");
            builder.and("status <> 'no_translation_required'");
        }
        if (filter.query() != null && !filter.query().isBlank()) {
            String q = filter.query().trim().toLowerCase(Locale.ROOT);
            builder.and("(INSTR(source_lc, ?) > 0 OR INSTR(translation_lc, ?) > 0"
                    + " OR INSTR(LOWER(row_key), ?) > 0 OR INSTR(LOWER(cell_id), ?) > 0"
                    + " OR INSTR(LOWER(file_path), ?) > 0 OR INSTR(LOWER(column_name), ?) > 0"
                    + " OR INSTR(LOWER(status), ?) > 0)", q, q, q, q, q, q, q);
        }
        return builder;
    }

    public long countByQuery(UUID projectId, EntryQuery filter) {
        SqlBuilder builder = whereClause(projectId, filter);
        Long total = jdbc.queryForObject(
                COUNT_BASE + builder.text(), Long.class, builder.params());
        return total == null ? 0 : total;
    }

    public long count(UUID projectId, EntryFilter filter) {
        return countByQuery(projectId, filter == null ? null : filter.toQuery());
    }

    public List<TranslationEntry> page(UUID projectId, EntryFilter filter, int offset, int limit) {
        return pageByQuery(projectId, filter == null ? null : filter.toQuery(), offset, limit);
    }

    public List<TranslationEntry> pageByQuery(UUID projectId, EntryQuery filter, int offset, int limit) {
        SqlBuilder builder = whereClause(projectId, filter).orderBy(ENTRY_ORDER).limitOffset(limit, offset);
        return jdbc.query(SELECT_BASE + builder.text(), ROW_MAPPER, builder.params());
    }

    public record EntryPage(List<TranslationEntry> entries, long total) {
    }

    private record EntryPageRow(TranslationEntry entry, long total) {
    }

    public EntryPage pageWithTotal(UUID projectId, EntryQuery filter, int offset, int limit) {
        SqlBuilder builder = whereClause(projectId, filter).orderBy(ENTRY_ORDER).limitOffset(limit, offset);
        List<EntryPageRow> rows = jdbc.query(
                SELECT_PAGE_WITH_TOTAL + builder.text(), PAGE_ROW_MAPPER, builder.params());
        List<TranslationEntry> entries = rows.stream().map(EntryPageRow::entry).toList();
        long total = rows.isEmpty() ? 0 : rows.get(0).total();
        return new EntryPage(entries, total);
    }

    public List<TranslationEntry> rowGroupWindow(UUID projectId, String file, String query,
            int offsetGroups, int limitGroups) {
        EntryQuery filter = new EntryQuery(null, List.of(), file, query, false);
        int from = Math.max(0, offsetGroups);
        int take = Math.max(0, limitGroups);
        SqlBuilder groups = whereClause(projectId, filter).orderBy("row_index").limitOffset(take, from);
        List<Integer> rowIndexes = jdbc.query(
                SELECT_ROW_GROUPS + groups.text(),
                (rs, i) -> rs.getInt("row_index"), groups.params());
        if (rowIndexes.isEmpty()) {
            return List.of();
        }
        SqlBuilder cells = whereClause(projectId, new EntryQuery(null, List.of(), file, null, false))
                .andIn("row_index", rowIndexes)
                .orderBy("row_index, column_index");
        return jdbc.query(SELECT_ROW_GROUP_CELLS + cells.text(), ROW_MAPPER, cells.params());
    }

    public long countRowGroups(UUID projectId, String file, String query) {
        EntryQuery filter = new EntryQuery(null, List.of(), file, query, false);
        SqlBuilder builder = whereClause(projectId, filter);
        Long total = jdbc.queryForObject(COUNT_ROW_GROUPS + builder.text(), Long.class, builder.params());
        return total == null ? 0 : total;
    }

    public long rowGroupPosition(UUID projectId, String file, int rowIndex, String query) {
        EntryQuery filter = new EntryQuery(null, List.of(), file, query, false);
        SqlBuilder builder = whereClause(projectId, filter).and("row_index < ?", rowIndex);
        Long position = jdbc.queryForObject(COUNT_ROW_GROUPS + builder.text(), Long.class, builder.params());
        return position == null ? 0 : position;
    }

    public TranslationEntry nextNeedsWork(UUID projectId, String file, int afterRow,
            int afterCol, String query) {
        EntryQuery filter = new EntryQuery(null, List.of(), file, query, false);
        SqlBuilder builder = whereClause(projectId, filter)
                .and(NEEDS_WORK_PREDICATE)
                .and("(row_index > ? OR (row_index = ? AND column_index > ?))",
                        afterRow, afterRow, afterCol)
                .orderBy("row_index, column_index")
                .limitOffset(1, 0);
        List<TranslationEntry> rows = jdbc.query(SELECT_ROW_GROUP_CELLS + builder.text(), ROW_MAPPER,
                builder.params());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public TranslationEntry findById(UUID id) {
        SqlBuilder builder = SqlBuilder.where("id = ?", id);
        List<TranslationEntry> rows =
                jdbc.query(SELECT_BASE + builder.text(), ROW_MAPPER, builder.params());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public TranslationEntry findByCell(UUID projectId, String cellId) {
        SqlBuilder builder = whereClause(projectId, null).and("cell_id = ?", cellId);
        List<TranslationEntry> rows =
                jdbc.query(SELECT_BASE + builder.text(), ROW_MAPPER, builder.params());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public TranslationEntry findByUuid(UUID projectId, UUID entryId) {
        SqlBuilder builder = whereClause(projectId, null).and("id = ?", entryId);
        List<TranslationEntry> rows =
                jdbc.query(SELECT_BASE + builder.text(), ROW_MAPPER, builder.params());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public record TranslatedCell(
            String cellId,
            String file,
            String source,
            String translation,
            String status,
            String columnName) {
    }

    private static final RowMapper<TranslatedCell> TRANSLATED_CELL_MAPPER = (rs, i) -> new TranslatedCell(
            rs.getString(1), rs.getString(2), rs.getString(3),
            rs.getString(4), rs.getString(5), rs.getString(6));

    public List<TranslatedCell> translatedCells(UUID projectId) {
        return jdbc.query(SELECT_TRANSLATED_CELLS, TRANSLATED_CELL_MAPPER,
                projectId);
    }

    public List<TranslationEntry> translatedByFile(UUID projectId, String fileId) {
        return jdbc.query(SELECT_TRANSLATED_BY_FILE, ROW_MAPPER,
                projectId, ProjectRepository.uuidOf(fileId));
    }

    public List<String> translatedFilePaths(UUID projectId) {
        return jdbc.query(SELECT_TRANSLATED_FILE_PATHS,
                (rs, i) -> rs.getString("file"), projectId);
    }

    private static final String DELTA_ORDER = "updated_at, cell_id";

    public List<TranslationEntry> deltaPage(UUID projectId, String sinceUpdatedAt, String sinceCellId,
            List<String> files, int limit) {
        SqlBuilder builder = SqlBuilder.where("project_id = ?", projectId);
        builder.and("(TRIM(translation) <> '' OR status <> 'untranslated')");
        if (sinceUpdatedAt != null && !sinceUpdatedAt.isBlank()) {
            builder.and("(updated_at > ? OR (updated_at = ? AND cell_id > ?))",
                    sinceUpdatedAt, sinceUpdatedAt, sinceCellId == null ? "" : sinceCellId);
        }
        if (files != null && !files.isEmpty()) {
            builder.andIn("file_path", files);
        }
        builder.orderBy(DELTA_ORDER).limitOffset(limit, 0);
        return jdbc.query(SELECT_BASE + builder.text(), ROW_MAPPER, builder.params());
    }

    public Map<String, TranslationEntry> findByCells(UUID projectId, List<String> cellIds) {
        Map<String, TranslationEntry> result = new LinkedHashMap<>();
        if (cellIds == null || cellIds.isEmpty()) {
            return result;
        }
        SqlBuilder builder = SqlBuilder.where("project_id = ?", projectId).andIn("cell_id", cellIds);
        for (TranslationEntry entry : jdbc.query(SELECT_BASE + builder.text(), ROW_MAPPER, builder.params())) {
            result.put(entry.getId(), entry);
        }
        return result;
    }

    public List<TranslationEntry> pending(UUID projectId, List<String> files) {
        SqlBuilder builder = whereClause(projectId, null).and("TRIM(translation) = ''")
                .and("status <> 'no_translation_required'");
        if (files != null && !files.isEmpty()) {
            builder.andIn("file_path", files.stream().map(f -> f.replace('\\', '/')).toList());
        }
        builder.orderBy(ENTRY_ORDER);
        return jdbc.query(SELECT_BASE + builder.text(), ROW_MAPPER, builder.params());
    }

    public Map<String, Long> pendingByFile(UUID projectId) {
        return jdbc.query(
                SELECT_PENDING_BY_FILE,
                new Object[]{projectId},
                rs -> {
                    Map<String, Long> out = new LinkedHashMap<>();
                    while (rs.next()) {
                        out.put(rs.getString(1), rs.getLong(2));
                    }
                    return out;
                });
    }

    public long remainingCount(UUID projectId) {
        SqlBuilder builder = whereClause(projectId, null).and("TRIM(translation) = ''")
                .and("status <> 'no_translation_required'");
        Long total = jdbc.queryForObject(
                COUNT_BASE + builder.text(), Long.class, builder.params());
        return total == null ? 0 : total;
    }

    public long translatedCount(UUID projectId) {
        SqlBuilder builder = whereClause(projectId, null)
                .and("TRIM(translation) <> ''")
                .and("status <> 'stale'");
        Long total = jdbc.queryForObject(
                COUNT_BASE + builder.text(), Long.class, builder.params());
        return total == null ? 0 : total;
    }

    public int updateTranslation(UUID id, String translation, String status, String updatedAt) {
        return jdbc.update(UPDATE_TRANSLATION,
                translation, lowercase(translation), status, updatedAt, id);
    }

    /** Compatibility overload for importers that still carry UUIDs as strings. */
    public int updateTranslation(String id, String translation, String status, String updatedAt) {
        return updateTranslation(ProjectRepository.uuidOf(id), translation, status, updatedAt);
    }

    public record TranslationWrite(
            String entryId,
            String oldTranslation,
            String oldStatus,
            String newTranslation,
            String newStatus) {
    }

    public void batchWriteTranslations(UUID projectId, List<TranslationWrite> writes,
            String origin, String now) {
        List<Object[]> updates = new ArrayList<>(writes.size());
        List<Object[]> history = new ArrayList<>(writes.size());
        for (TranslationWrite write : writes) {
            java.util.UUID entry = ProjectRepository.uuidOf(write.entryId());
            updates.add(new Object[]{write.newTranslation(), lowercase(write.newTranslation()),
                    write.newStatus(), now, entry});
            history.add(new Object[]{projectId, entry, write.oldTranslation(), write.oldStatus(),
                    write.newTranslation(), write.newStatus(), origin, now, now});
        }
        if (updates.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(UPDATE_TRANSLATION, updates);
        jdbc.batchUpdate(INSERT_HISTORY, history);
    }

    public void insertHistory(UUID projectId, UUID entryId,
            String oldTranslation, String oldStatus,
            String newTranslation, String newStatus, String origin, String now) {
        jdbc.update(INSERT_HISTORY,
                projectId, entryId,
                oldTranslation, oldStatus, newTranslation, newStatus, origin, now, now);
    }

    /** Compatibility overload for callers that still carry UUIDs as strings. */
    public void insertHistory(UUID projectId, String entryId,
            String oldTranslation, String oldStatus,
            String newTranslation, String newStatus, String origin, String now) {
        insertHistory(projectId, ProjectRepository.uuidOf(entryId), oldTranslation, oldStatus,
                newTranslation, newStatus, origin, now);
    }

    public void deleteStale(UUID projectId, String stamp) {
        SqlBuilder builder = SqlBuilder.deleteFrom("entries")
                .and("project_id = ?", projectId)
                .and("updated_at <> ?", stamp);
        jdbc.update(builder.text(), builder.params());
    }

    public int deleteEntriesBatch(UUID projectId, int limit) {
        return jdbc.update(DELETE_ENTRIES_BATCH, projectId, limit);
    }

    public void deleteStaleEntries(UUID projectId, List<UUID> fileIds, String stamp) {
        if (fileIds == null || fileIds.isEmpty()) {
            return;
        }
        SqlBuilder builder = SqlBuilder.deleteFrom("entries")
                .and("project_id = ?", projectId)
                .andIn("file_id", fileIds)
                .and("updated_at <> ?", stamp);
        jdbc.update(builder.text(), builder.params());
    }

    public void deleteEntriesByFiles(UUID projectId, List<UUID> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return;
        }
        SqlBuilder builder = SqlBuilder.deleteFrom("entries")
                .and("project_id = ?", projectId)
                .andIn("file_id", fileIds);
        jdbc.update(builder.text(), builder.params());
    }

    public void batchUpsert(UUID projectId, Map<String, String> fileIds,
            List<TranslationEntry> entries, String now) {
        List<Object[]> batch = new ArrayList<>(entries.size());
        for (TranslationEntry entry : entries) {
            validateCell(entry);
            String fileId = fileIds.get(entry.getFile());
            if (fileId == null) {
                throw new HarmoniaSuiteBadRequestException("Unknown file: " + entry.getFile());
            }
            batch.add(new Object[]{
                    projectId, ProjectRepository.uuidOf(fileId), entry.getId(), entry.getFile(),
                    entry.getRowKey() == null ? "" : entry.getRowKey(), entry.getColumnIndex(),
                    entry.getColumnName() == null ? "" : entry.getColumnName(), entry.getRowIndex(),
                    entry.getSource() == null ? "" : entry.getSource(),
                    lowercase(entry.getSource()),
                    entry.getTranslation() == null ? "" : entry.getTranslation(),
                    lowercase(entry.getTranslation()),
                    entry.getStatus() == null ? "untranslated" : entry.getStatus(), now, now});
        }
        if (batch.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(INSERT_ENTRY, batch);
    }

    public List<Map<String, Object>> progressByFile(UUID projectId) {
        return jdbc.queryForList(SELECT_PROGRESS, projectId);
    }

    private static String lowercase(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
