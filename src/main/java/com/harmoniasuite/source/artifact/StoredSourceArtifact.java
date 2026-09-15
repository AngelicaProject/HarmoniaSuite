package com.harmoniasuite.source.artifact;

import java.util.Arrays;
import java.util.Objects;

/** Result of atomically materializing an artifact in the configured blob store. */
public record StoredSourceArtifact(
        String storageKey,
        long uncompressedSize,
        long compressedSize,
        byte[] hxsFileHash,
        byte[] artifactHash) {

    public StoredSourceArtifact {
        Objects.requireNonNull(storageKey, "storageKey");
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
