package com.harmoniasuite.source.artifact;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/** JDBC registry for immutable artifact metadata in the canonical database. */
public final class JdbcSourceArtifactRegistry implements SourceArtifactRegistry {

    private static final String COLUMNS = "a.id, a.snapshot_db_id, a.storage_key, a.compression, "
            + "a.uncompressed_size, a.compressed_size, a.hxs_file_hash, a.artifact_hash, "
            + "a.created_at_ms";

    private final JdbcTemplate jdbc;

    public JdbcSourceArtifactRegistry(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<SourceArtifact> findBySnapshotId(String snapshotId) {
        Objects.requireNonNull(snapshotId, "snapshotId");
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM source_artifacts a JOIN source_snapshots s"
                        + " ON s.id = a.snapshot_db_id WHERE s.snapshot_id = ?",
                mapper(), snapshotId).stream().findFirst();
    }

    @Override
    public Optional<SourceArtifact> findBySnapshotDbId(long snapshotDbId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM source_artifacts a"
                        + " WHERE a.snapshot_db_id = ?", mapper(), snapshotDbId)
                .stream().findFirst();
    }

    @Override
    public SourceArtifact register(SourceArtifact artifact) {
        validate(artifact);
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
                statement.setString(3, artifact.compression());
                statement.setLong(4, artifact.uncompressedSize());
                statement.setLong(5, artifact.compressedSize());
                statement.setBytes(6, artifact.hxsFileHash());
                statement.setBytes(7, artifact.artifactHash());
                statement.setLong(8, artifact.createdAtMs());
                return statement;
            }, keyHolder);
            Number id = keyHolder.getKey();
            if (id == null) {
                return findBySnapshotDbId(artifact.snapshotDbId()).orElseThrow(
                        () -> new IllegalStateException("artifact insert returned no ID"));
            }
            return new SourceArtifact(id.longValue(), artifact.snapshotDbId(), artifact.storageKey(),
                    artifact.compression(), artifact.uncompressedSize(), artifact.compressedSize(),
                    artifact.hxsFileHash(), artifact.artifactHash(), artifact.createdAtMs());
        } catch (DataAccessException exception) {
            if (!isConstraintViolation(exception)) {
                throw exception;
            }
            SourceArtifact existing = findBySnapshotDbId(artifact.snapshotDbId())
                    .orElseThrow(() -> new IllegalStateException(
                            "artifact uniqueness race completed without a registry row", exception));
            if (!same(existing, artifact)) {
                throw new IllegalStateException("immutable artifact metadata conflicts", exception);
            }
            return existing;
        }
    }

    private static void validate(SourceArtifact artifact) {
        Objects.requireNonNull(artifact, "artifact");
        if (!"zstd".equals(artifact.compression()) || artifact.uncompressedSize() < 0
                || artifact.compressedSize() < 0 || artifact.hxsFileHash().length != 32
                || artifact.artifactHash().length != 32) {
            throw new IllegalArgumentException("artifact metadata is invalid");
        }
        Path key = Path.of(artifact.storageKey());
        if (key.isAbsolute() || key.normalize().startsWith("..")) {
            throw new IllegalArgumentException("artifact storage key must be relative");
        }
    }

    private static boolean same(SourceArtifact left, SourceArtifact right) {
        return left.snapshotDbId() == right.snapshotDbId()
                && left.storageKey().equals(right.storageKey())
                && left.compression().equals(right.compression())
                && left.uncompressedSize() == right.uncompressedSize()
                && left.compressedSize() == right.compressedSize()
                && java.security.MessageDigest.isEqual(left.hxsFileHash(), right.hxsFileHash())
                && java.security.MessageDigest.isEqual(left.artifactHash(), right.artifactHash());
    }

    private static boolean isConstraintViolation(DataAccessException exception) {
        if (exception instanceof DuplicateKeyException) {
            return true;
        }
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
