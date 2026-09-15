package com.harmoniasuite.source.application.model;

import com.harmoniasuite.source.domain.Sha256Digest;
import java.util.Objects;

/** Result of atomically materializing an artifact in the configured blob store. */
public record StoredSourceArtifact(
        String storageKey,
        long uncompressedSize,
        long compressedSize,
        Sha256Digest hxsFileHash,
        Sha256Digest artifactHash) {

    public StoredSourceArtifact {
        Objects.requireNonNull(storageKey, "storageKey");
        Objects.requireNonNull(hxsFileHash, "hxsFileHash");
        Objects.requireNonNull(artifactHash, "artifactHash");
        Objects.requireNonNull(hxsFileHash, "hxsFileHash");
        Objects.requireNonNull(artifactHash, "artifactHash");
    }

    public StoredSourceArtifact(String storageKey, long uncompressedSize, long compressedSize,
                                byte[] hxsFileHash, byte[] artifactHash) {
        this(storageKey, uncompressedSize, compressedSize,
                Sha256Digest.of(hxsFileHash), Sha256Digest.of(artifactHash));
    }
}
