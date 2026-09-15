package com.harmoniasuite.source.domain;

import java.time.Instant;
import java.util.Objects;

/** Registered immutable metadata for one canonical snapshot artifact. */
public record SourceArtifact(
        long id,
        long snapshotDbId,
        String storageKey,
        SourceArtifactCompression compression,
        long uncompressedSize,
        long compressedSize,
        Sha256Digest hxsFileHash,
        Sha256Digest artifactHash,
        Instant createdAt) {

    public SourceArtifact {
        Objects.requireNonNull(storageKey, "storageKey");
        Objects.requireNonNull(compression, "compression");
        Objects.requireNonNull(hxsFileHash, "hxsFileHash");
        Objects.requireNonNull(artifactHash, "artifactHash");
        Objects.requireNonNull(createdAt, "createdAt");
        if (uncompressedSize < 0 || compressedSize < 0) {
            throw new IllegalArgumentException("artifact sizes must be non-negative");
        }
        if (compression != SourceArtifactCompression.ZSTD) {
            throw new IllegalArgumentException("unsupported artifact compression");
        }
        if (storageKey.startsWith("/") || storageKey.contains("..")) {
            throw new IllegalArgumentException("artifact storage key must be relative");
        }
    }

    public SourceArtifact(long id, long snapshotDbId, String storageKey, String compression,
                          long uncompressedSize, long compressedSize, byte[] hxsFileHash,
                          byte[] artifactHash, long createdAtMs) {
        this(id, snapshotDbId, storageKey, parseCompression(compression), uncompressedSize,
                compressedSize, Sha256Digest.of(hxsFileHash), Sha256Digest.of(artifactHash),
                Instant.ofEpochMilli(createdAtMs));
    }

    private static SourceArtifactCompression parseCompression(String compression) {
        if (!SourceArtifactCompression.ZSTD.wireValue().equals(compression)) {
            throw new IllegalArgumentException("unsupported artifact compression");
        }
        return SourceArtifactCompression.ZSTD;
    }
}
