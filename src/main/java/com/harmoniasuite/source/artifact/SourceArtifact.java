package com.harmoniasuite.source.artifact;

import java.util.Arrays;
import java.util.Objects;

/** Registered immutable metadata for one canonical snapshot artifact. */
public record SourceArtifact(
        long id,
        long snapshotDbId,
        String storageKey,
        String compression,
        long uncompressedSize,
        long compressedSize,
        byte[] hxsFileHash,
        byte[] artifactHash,
        long createdAtMs) {

    public SourceArtifact {
        Objects.requireNonNull(storageKey, "storageKey");
        Objects.requireNonNull(compression, "compression");
        Objects.requireNonNull(hxsFileHash, "hxsFileHash");
        Objects.requireNonNull(artifactHash, "artifactHash");
        hxsFileHash = Arrays.copyOf(hxsFileHash, hxsFileHash.length);
        artifactHash = Arrays.copyOf(artifactHash, artifactHash.length);
    }

    @Override
    public byte[] hxsFileHash() {
        return Arrays.copyOf(hxsFileHash, hxsFileHash.length);
    }

    @Override
    public byte[] artifactHash() {
        return Arrays.copyOf(artifactHash, artifactHash.length);
    }
}
