package com.harmoniasuite.source.upload;

import java.util.Arrays;
import java.util.Objects;

/** Persistent state and untrusted claim for one server-generated upload session. */
public record SourceUploadSession(
        String uploadId,
        SourceUploadState state,
        TransportEncoding transportEncoding,
        long uploadSize,
        long uncompressedSize,
        long receivedBytes,
        SourceUploadClaim claim,
        byte[] transportHash,
        Long snapshotDbId,
        String errorCode,
        String errorMessage,
        long createdAtMs,
        long updatedAtMs) {

    public SourceUploadSession {
        Objects.requireNonNull(uploadId, "uploadId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(transportEncoding, "transportEncoding");
        Objects.requireNonNull(claim, "claim");
        transportHash = transportHash == null ? null
                : Arrays.copyOf(transportHash, transportHash.length);
    }

    @Override
    public byte[] transportHash() {
        return transportHash == null ? null : Arrays.copyOf(transportHash, transportHash.length);
    }
}
