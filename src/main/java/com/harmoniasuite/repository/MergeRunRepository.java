package com.harmoniasuite.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MergeRunRepository {

    private static final String INSERT_RUN = """
            INSERT INTO merge_runs (
                project_id, started_at, status, input_root, output_root,
                files_total, files_processed, translated_cells, result_json,
                created_at, updated_at
            )
            VALUES (?, ?, 'running', ?, ?, ?, 0, 0, '[]', ?, ?)
            RETURNING id""";

    private static final String FINISH_RUN = """
            UPDATE merge_runs SET
                finished_at = ?, status = ?, files_processed = ?,
                translated_cells = ?, result_json = ?, updated_at = ?
            WHERE id = ?""";

    private static final String SELECT_LAST_RUN = """
            SELECT started_at, finished_at, status, input_root, output_root,
                   files_total, files_processed, translated_cells, result_json
            FROM merge_runs WHERE project_id = ? ORDER BY created_at DESC, id DESC LIMIT 1""";

    private final JdbcTemplate jdbc;

    public MergeRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String start(UUID projectId, String startedAt, String inputRoot, String outputRoot,
            int filesTotal) {
        return jdbc.queryForObject(INSERT_RUN, (rs, i) -> rs.getString(1),
                projectId, startedAt, inputRoot, outputRoot,
                filesTotal, startedAt, startedAt);
    }

    public void finish(String id, String finishedAt, String status,
            int filesProcessed, int translatedCells, String resultJson) {
        jdbc.update(FINISH_RUN,
                finishedAt, status, filesProcessed, translatedCells, resultJson, finishedAt,
                ProjectRepository.uuidOf(id));
    }

    public Map<String, Object> last(UUID projectId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(SELECT_LAST_RUN, projectId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("status", row.get("status"));
        state.put("started_at", row.get("started_at"));
        state.put("finished_at", row.get("finished_at"));
        state.put("input_root", row.get("input_root"));
        state.put("output_root", row.get("output_root"));
        state.put("files_total", ((Number) row.get("files_total")).intValue());
        state.put("files_processed", ((Number) row.get("files_processed")).intValue());
        state.put("translated_cells", ((Number) row.get("translated_cells")).intValue());
        state.put("files", row.get("result_json"));
        return state;
    }
}
