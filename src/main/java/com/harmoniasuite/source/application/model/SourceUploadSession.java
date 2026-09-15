package com.harmoniasuite.source.application.model;

import com.harmoniasuite.source.domain.SourceSnapshotMetadata;
import com.harmoniasuite.source.domain.Sha256Digest;
import com.harmoniasuite.source.domain.SourceUploadErrorCode;
import com.harmoniasuite.source.domain.SourceUploadState;
import com.harmoniasuite.source.domain.TransportEncoding;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persistent state and untrusted claim for one server-generated upload session. */
public record SourceUploadSession(
        UUID uploadId,
        SourceUploadState state,
        TransportEncoding transportEncoding,
        long uploadSize,
        long uncompressedSize,
        long receivedBytes,
        SourceSnapshotMetadata metadata,
        Sha256Digest transportHash,
        Long snapshotDbId,
        SourceUploadErrorCode errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {

    public SourceUploadSession {
        Objects.requireNonNull(uploadId, "uploadId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(transportEncoding, "transportEncoding");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (uploadSize <= 0 || uncompressedSize <= 0 || receivedBytes < 0
                || receivedBytes > uploadSize) {
            throw new IllegalArgumentException("upload sizes and received bytes are invalid");
        }
        if (transportEncoding == TransportEncoding.IDENTITY && uploadSize != uncompressedSize) {
            throw new IllegalArgumentException("identity upload sizes must match");
        }
    }
}
