package com.harmoniasuite.source.infrastructure.persistence;

import com.harmoniasuite.source.application.model.SourceUploadSession;
import com.harmoniasuite.source.application.port.SourceUploadSessionRepository;
import com.harmoniasuite.source.domain.Sha256Digest;
import com.harmoniasuite.source.domain.SourceSnapshotMetadata;
import com.harmoniasuite.source.domain.SourceUploadErrorCode;
import com.harmoniasuite.source.domain.SourceUploadState;
import com.harmoniasuite.source.domain.TransportEncoding;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** JDBC adapter for the upload state machine; database timestamps remain epoch milliseconds. */
public final class JdbcSourceUploadSessionRepository implements SourceUploadSessionRepository {

    private static final String COLUMNS = "upload_id, state, transport_encoding, upload_size, "
            + "uncompressed_size, received_bytes, claimed_hxs_version, claimed_game_version, "
            + "claimed_language, claimed_scope, claimed_snapshot_id, claimed_content_id, "
            + "claimed_extractor_version, claimed_lumina_version, claimed_sheet_count, "
            + "claimed_row_count, claimed_string_cell_count, transport_hash, snapshot_db_id, "
            + "error_code, error_message, created_at_ms, updated_at_ms";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcSourceUploadSessionRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
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
            statement.setString(index++, session.uploadId().toString());
            statement.setString(index++, session.state().name());
            statement.setString(index++, session.transportEncoding().wireValue());
            statement.setLong(index++, session.uploadSize());
            statement.setLong(index++, session.uncompressedSize());
            statement.setLong(index++, session.receivedBytes());
            SourceSnapshotMetadata metadata = session.metadata();
            statement.setInt(index++, metadata.hxsVersion());
            statement.setString(index++, metadata.gameVersion());
            statement.setString(index++, metadata.language());
            statement.setString(index++, metadata.scope());
            statement.setString(index++, metadata.snapshotId().value());
            statement.setString(index++, metadata.contentId().value());
            statement.setString(index++, metadata.extractorVersion());
            statement.setString(index++, metadata.luminaVersion());
            statement.setLong(index++, metadata.sheetCount());
            statement.setLong(index++, metadata.rowCount());
            statement.setLong(index++, metadata.stringCellCount());
            Sha256Digest hash = session.transportHash();
            statement.setBytes(index++, hash == null ? null : hash.bytes());
            if (session.snapshotDbId() == null) statement.setNull(index++, Types.BIGINT);
            else statement.setLong(index++, session.snapshotDbId());
            statement.setString(index++, session.errorCode() == null ? null : session.errorCode().name());
            statement.setString(index++, session.errorMessage());
            statement.setLong(index++, session.createdAt().toEpochMilli());
            statement.setLong(index, session.updatedAt().toEpochMilli());
            return statement;
        });
        return session;
    }

    @Override
    public Optional<SourceUploadSession> find(UUID uploadId) {
        Objects.requireNonNull(uploadId, "uploadId");
        return jdbc.query("SELECT " + COLUMNS + " FROM source_upload_sessions WHERE upload_id = ?",
                mapper(), uploadId.toString()).stream().findFirst();
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
    public List<SourceUploadSession> findExpired(SourceUploadState state, Instant cutoff) {
        return jdbc.query("SELECT " + COLUMNS + " FROM source_upload_sessions"
                + " WHERE state = ? AND updated_at_ms < ? ORDER BY updated_at_ms",
                mapper(), state.name(), cutoff.toEpochMilli());
    }

    @Override
    public boolean transition(UUID uploadId, SourceUploadState expected, SourceUploadState target) {
        if (!expected.canTransitionTo(target)) throw new IllegalStateException("invalid upload state transition");
        return jdbc.update("UPDATE source_upload_sessions SET state = ?, updated_at_ms = ?"
                        + " WHERE upload_id = ? AND state = ?",
                target.name(), clock.millis(), uploadId.toString(), expected.name()) == 1;
    }

    @Override
    public boolean requeueProcessing(UUID uploadId) {
        return jdbc.update("UPDATE source_upload_sessions SET state = 'QUEUED', updated_at_ms = ?"
                        + " WHERE upload_id = ? AND state IN ('VERIFYING', 'MATERIALIZING', 'STORING')",
                clock.millis(), uploadId.toString()) == 1;
    }

    @Override
    public boolean updateReceivedBytes(UUID uploadId, long receivedBytes) {
        return jdbc.update("UPDATE source_upload_sessions SET received_bytes = ?, updated_at_ms = ?"
                        + " WHERE upload_id = ? AND state = 'UPLOADING'",
                receivedBytes, clock.millis(), uploadId.toString()) == 1;
    }

    @Override
    public boolean setTransportHash(UUID uploadId, Sha256Digest transportHash) {
        return jdbc.update("UPDATE source_upload_sessions SET transport_hash = ?, updated_at_ms = ?"
                        + " WHERE upload_id = ? AND state IN ('QUEUED', 'VERIFYING', 'MATERIALIZING', 'STORING')",
                transportHash.bytes(), clock.millis(), uploadId.toString()) == 1;
    }

    @Override
    public boolean setSnapshotDbId(UUID uploadId, long snapshotDbId) {
        return jdbc.update("UPDATE source_upload_sessions SET snapshot_db_id = ?, updated_at_ms = ?"
                        + " WHERE upload_id = ?", snapshotDbId, clock.millis(), uploadId.toString()) == 1;
    }

    @Override
    public boolean fail(UUID uploadId, SourceUploadErrorCode errorCode, String errorMessage) {
        return jdbc.update("UPDATE source_upload_sessions SET state = 'FAILED', error_code = ?,"
                        + " error_message = ?, updated_at_ms = ? WHERE upload_id = ?"
                        + " AND state <> 'COMPLETED' AND state <> 'FAILED'",
                errorCode.name(), sanitize(errorMessage), clock.millis(), uploadId.toString()) == 1;
    }

    @Override
    public void delete(UUID uploadId) {
        jdbc.update("DELETE FROM source_upload_sessions WHERE upload_id = ?", uploadId.toString());
    }

    private static String sanitize(String value) {
        if (value == null) return null;
        String oneLine = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return oneLine.length() > 500 ? oneLine.substring(0, 500) : oneLine;
    }

    private static RowMapper<SourceUploadSession> mapper() {
        return (resultSet, rowNum) -> new SourceUploadSession(
                UUID.fromString(resultSet.getString("upload_id")),
                SourceUploadState.valueOf(resultSet.getString("state")),
                TransportEncoding.parse(resultSet.getString("transport_encoding")),
                resultSet.getLong("upload_size"), resultSet.getLong("uncompressed_size"),
                resultSet.getLong("received_bytes"),
                new SourceSnapshotMetadata(resultSet.getInt("claimed_hxs_version"),
                        resultSet.getString("claimed_game_version"), resultSet.getString("claimed_language"),
                        resultSet.getString("claimed_scope"), resultSet.getString("claimed_snapshot_id"),
                        resultSet.getString("claimed_content_id"), resultSet.getString("claimed_extractor_version"),
                        resultSet.getString("claimed_lumina_version"), resultSet.getLong("claimed_sheet_count"),
                        resultSet.getLong("claimed_row_count"), resultSet.getLong("claimed_string_cell_count")),
                resultSet.getBytes("transport_hash") == null ? null : Sha256Digest.of(resultSet.getBytes("transport_hash")),
                snapshotDbId(resultSet), errorCode(resultSet.getString("error_code")),
                resultSet.getString("error_message"), Instant.ofEpochMilli(resultSet.getLong("created_at_ms")),
                Instant.ofEpochMilli(resultSet.getLong("updated_at_ms")));
    }

    private static SourceUploadErrorCode errorCode(String value) {
        return value == null ? null : SourceUploadErrorCode.valueOf(value);
    }

    private static Long snapshotDbId(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        long value = resultSet.getLong("snapshot_db_id");
        return resultSet.wasNull() ? null : value;
    }
}
