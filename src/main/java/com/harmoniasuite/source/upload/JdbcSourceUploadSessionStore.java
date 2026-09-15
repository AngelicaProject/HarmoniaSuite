package com.harmoniasuite.source.upload;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** JDBC persistence for resumable upload state and claims. */
public final class JdbcSourceUploadSessionStore implements SourceUploadSessionStore {

    private static final String COLUMNS = "upload_id, state, transport_encoding, upload_size, "
            + "uncompressed_size, received_bytes, claimed_hxs_version, claimed_game_version, "
            + "claimed_language, claimed_scope, claimed_snapshot_id, claimed_content_id, "
            + "claimed_extractor_version, claimed_lumina_version, claimed_sheet_count, "
            + "claimed_row_count, claimed_string_cell_count, transport_hash, snapshot_db_id, "
            + "error_code, error_message, created_at_ms, updated_at_ms";

    private final JdbcTemplate jdbc;

    public JdbcSourceUploadSessionStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public SourceUploadSession create(SourceUploadSession session) {
        Objects.requireNonNull(session, "session");
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO source_upload_sessions
                        (upload_id, state, transport_encoding, upload_size, uncompressed_size,
                         received_bytes, claimed_hxs_version, claimed_game_version,
                         claimed_language, claimed_scope, claimed_snapshot_id, claimed_content_id,
                         claimed_extractor_version, claimed_lumina_version, claimed_sheet_count,
                         claimed_row_count, claimed_string_cell_count, transport_hash,
                         snapshot_db_id, error_code, error_message, created_at_ms, updated_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            int index = 1;
            statement.setString(index++, session.uploadId());
            statement.setString(index++, session.state().name());
            statement.setString(index++, session.transportEncoding().wireValue());
            statement.setLong(index++, session.uploadSize());
            statement.setLong(index++, session.uncompressedSize());
            statement.setLong(index++, session.receivedBytes());
            SourceUploadClaim claim = session.claim();
            statement.setInt(index++, claim.hxsVersion());
            statement.setString(index++, claim.gameVersion());
            statement.setString(index++, claim.language());
            statement.setString(index++, claim.scope());
            statement.setString(index++, claim.snapshotId());
            statement.setString(index++, claim.contentId());
            statement.setString(index++, claim.extractorVersion());
            statement.setString(index++, claim.luminaVersion());
            statement.setLong(index++, claim.sheetCount());
            statement.setLong(index++, claim.rowCount());
            statement.setLong(index++, claim.stringCellCount());
            statement.setBytes(index++, session.transportHash());
            if (session.snapshotDbId() == null) {
                statement.setNull(index++, Types.BIGINT);
            } else {
                statement.setLong(index++, session.snapshotDbId());
            }
            statement.setString(index++, session.errorCode());
            statement.setString(index++, session.errorMessage());
            statement.setLong(index++, session.createdAtMs());
            statement.setLong(index, session.updatedAtMs());
            return statement;
        });
        return session;
    }

    @Override
    public Optional<SourceUploadSession> find(String uploadId) {
        Objects.requireNonNull(uploadId, "uploadId");
        return jdbc.query("SELECT " + COLUMNS + " FROM source_upload_sessions WHERE upload_id = ?",
                mapper(), uploadId).stream().findFirst();
    }

    @Override
    public List<SourceUploadSession> findProcessing() {
        return jdbc.query("SELECT " + COLUMNS + " FROM source_upload_sessions"
                + " WHERE state IN ('QUEUED', 'VERIFYING', 'MATERIALIZING', 'STORING')"
                + " ORDER BY created_at_ms", mapper());
    }

    @Override
    public List<SourceUploadSession> findQueued() {
        return jdbc.query("SELECT " + COLUMNS + " FROM source_upload_sessions"
                + " WHERE state = 'QUEUED' ORDER BY created_at_ms", mapper());
    }

    @Override
    public List<SourceUploadSession> findExpired(SourceUploadState state, long cutoffMs) {
        return jdbc.query("SELECT " + COLUMNS + " FROM source_upload_sessions"
                + " WHERE state = ? AND updated_at_ms < ? ORDER BY updated_at_ms",
                mapper(), state.name(), cutoffMs);
    }

    @Override
    public boolean transition(String uploadId, SourceUploadState expected,
                              SourceUploadState target) {
        if (!expected.canTransitionTo(target)) {
            throw new IllegalStateException("invalid upload state transition");
        }
        return jdbc.update("UPDATE source_upload_sessions SET state = ?, updated_at_ms = ?"
                        + " WHERE upload_id = ? AND state = ?",
                target.name(), System.currentTimeMillis(), uploadId, expected.name()) == 1;
    }

    @Override
    public boolean requeueProcessing(String uploadId) {
        return jdbc.update("UPDATE source_upload_sessions SET state = 'QUEUED', updated_at_ms = ?"
                        + " WHERE upload_id = ? AND state IN"
                        + " ('VERIFYING', 'MATERIALIZING', 'STORING')",
                System.currentTimeMillis(), uploadId) == 1;
    }

    @Override
    public boolean updateReceivedBytes(String uploadId, long receivedBytes) {
        return jdbc.update("UPDATE source_upload_sessions SET received_bytes = ?, updated_at_ms = ?"
                        + " WHERE upload_id = ? AND state = 'UPLOADING'",
                receivedBytes, System.currentTimeMillis(), uploadId) == 1;
    }

    @Override
    public boolean setTransportHash(String uploadId, byte[] transportHash) {
        return jdbc.update("UPDATE source_upload_sessions SET transport_hash = ?, updated_at_ms = ?"
                        + " WHERE upload_id = ? AND state IN"
                        + " ('QUEUED', 'VERIFYING', 'MATERIALIZING', 'STORING')",
                transportHash, System.currentTimeMillis(), uploadId) == 1;
    }

    @Override
    public boolean setSnapshotDbId(String uploadId, long snapshotDbId) {
        return jdbc.update("UPDATE source_upload_sessions SET snapshot_db_id = ?, updated_at_ms = ?"
                        + " WHERE upload_id = ?", snapshotDbId, System.currentTimeMillis(), uploadId) == 1;
    }

    @Override
    public boolean fail(String uploadId, String errorCode, String errorMessage) {
        return jdbc.update("UPDATE source_upload_sessions SET state = 'FAILED', error_code = ?,"
                        + " error_message = ?, updated_at_ms = ? WHERE upload_id = ?"
                        + " AND state <> 'COMPLETED' AND state <> 'FAILED'",
                sanitize(errorCode), sanitize(errorMessage), System.currentTimeMillis(), uploadId) == 1;
    }

    @Override
    public void delete(String uploadId) {
        jdbc.update("DELETE FROM source_upload_sessions WHERE upload_id = ?", uploadId);
    }

    private static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String oneLine = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return oneLine.length() > 500 ? oneLine.substring(0, 500) : oneLine;
    }

    private static RowMapper<SourceUploadSession> mapper() {
        return (resultSet, rowNum) -> new SourceUploadSession(
                resultSet.getString("upload_id"),
                SourceUploadState.valueOf(resultSet.getString("state")),
                TransportEncoding.parse(resultSet.getString("transport_encoding")),
                resultSet.getLong("upload_size"), resultSet.getLong("uncompressed_size"),
                resultSet.getLong("received_bytes"),
                new SourceUploadClaim(resultSet.getInt("claimed_hxs_version"),
                        resultSet.getString("claimed_game_version"),
                        resultSet.getString("claimed_language"),
                        resultSet.getString("claimed_scope"),
                        resultSet.getString("claimed_snapshot_id"),
                        resultSet.getString("claimed_content_id"),
                        resultSet.getString("claimed_extractor_version"),
                        resultSet.getString("claimed_lumina_version"),
                        resultSet.getLong("claimed_sheet_count"),
                        resultSet.getLong("claimed_row_count"),
                        resultSet.getLong("claimed_string_cell_count")),
                resultSet.getBytes("transport_hash"),
                snapshotDbId(resultSet),
                resultSet.getString("error_code"), resultSet.getString("error_message"),
                resultSet.getLong("created_at_ms"), resultSet.getLong("updated_at_ms"));
    }

    private static Long snapshotDbId(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        long value = resultSet.getLong("snapshot_db_id");
        return resultSet.wasNull() ? null : value;
    }
}
