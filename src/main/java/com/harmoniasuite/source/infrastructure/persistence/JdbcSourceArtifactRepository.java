package com.harmoniasuite.source.infrastructure.persistence;

import com.harmoniasuite.source.application.port.SourceArtifactRepository;
import com.harmoniasuite.source.domain.Sha256Digest;
import com.harmoniasuite.source.domain.SourceArtifact;
import com.harmoniasuite.source.domain.SourceArtifactCompression;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/** JDBC adapter for immutable artifact metadata; raw database hashes remain BLOBs. */
public final class JdbcSourceArtifactRepository implements SourceArtifactRepository {

    private static final String COLUMNS = "a.id, a.snapshot_db_id, a.storage_key, a.compression, "
            + "a.uncompressed_size, a.compressed_size, a.hxs_file_hash, a.artifact_hash, a.created_at_ms";
    private final JdbcTemplate jdbc;

    public JdbcSourceArtifactRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<SourceArtifact> findBySnapshotId(String snapshotId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM source_artifacts a JOIN source_snapshots s"
                + " ON s.id = a.snapshot_db_id WHERE s.snapshot_id = ?", mapper(), snapshotId)
                .stream().findFirst();
    }

    @Override
    public Optional<SourceArtifact> findBySnapshotDbId(long snapshotDbId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM source_artifacts a WHERE a.snapshot_db_id = ?",
                mapper(), snapshotDbId).stream().findFirst();
    }

    @Override
    public SourceArtifact register(SourceArtifact artifact) {
        Objects.requireNonNull(artifact, "artifact");
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO source_artifacts
                            (snapshot_db_id, storage_key, compression, uncompressed_size,
                             compressed_size, hxs_file_hash, artifact_hash, created_at_ms)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, artifact.snapshotDbId());
                statement.setString(2, artifact.storageKey());
                statement.setString(3, artifact.compression().wireValue());
                statement.setLong(4, artifact.uncompressedSize());
                statement.setLong(5, artifact.compressedSize());
                statement.setBytes(6, artifact.hxsFileHash().bytes());
                statement.setBytes(7, artifact.artifactHash().bytes());
                statement.setLong(8, artifact.createdAt().toEpochMilli());
                return statement;
            }, keyHolder);
            Number id = keyHolder.getKey();
            return id == null ? findBySnapshotDbId(artifact.snapshotDbId()).orElseThrow()
                    : new SourceArtifact(id.longValue(), artifact.snapshotDbId(), artifact.storageKey(),
                    artifact.compression(), artifact.uncompressedSize(), artifact.compressedSize(),
                    artifact.hxsFileHash(), artifact.artifactHash(), artifact.createdAt());
        } catch (DataAccessException exception) {
            if (!isConstraintViolation(exception)) throw exception;
            SourceArtifact existing = findBySnapshotDbId(artifact.snapshotDbId()).orElseThrow();
            if (!same(existing, artifact)) throw new IllegalStateException("immutable artifact metadata conflicts", exception);
            return existing;
        }
    }

    private static boolean same(SourceArtifact left, SourceArtifact right) {
        return left.snapshotDbId() == right.snapshotDbId()
                && left.storageKey().equals(right.storageKey())
                && left.compression() == right.compression()
                && left.uncompressedSize() == right.uncompressedSize()
                && left.compressedSize() == right.compressedSize()
                && left.hxsFileHash().equals(right.hxsFileHash())
                && left.artifactHash().equals(right.artifactHash());
    }

    private static boolean isConstraintViolation(DataAccessException exception) {
        if (exception instanceof DuplicateKeyException) return true;
        String message = exception.getMessage();
        return message != null && message.toLowerCase().contains("unique");
    }

    private static RowMapper<SourceArtifact> mapper() {
        return (resultSet, rowNum) -> new SourceArtifact(
                resultSet.getLong("id"), resultSet.getLong("snapshot_db_id"),
                resultSet.getString("storage_key"), resultSet.getString("compression"),
                resultSet.getLong("uncompressed_size"), resultSet.getLong("compressed_size"),
                resultSet.getBytes("hxs_file_hash"), resultSet.getBytes("artifact_hash"),
                resultSet.getLong("created_at_ms"));
    }
}
