package com.harmoniasuite.source.application;

import com.harmoniasuite.source.application.exception.SourceUploadFailure;
import com.harmoniasuite.source.application.model.PreparedSourceUpload;
import com.harmoniasuite.source.application.model.SourceIngestionPropertiesView;
import com.harmoniasuite.source.application.model.SourceUploadSession;
import com.harmoniasuite.source.application.model.StoredSourceArtifact;
import com.harmoniasuite.source.application.port.SourceArtifactRepository;
import com.harmoniasuite.source.application.port.SourceArtifactStorage;
import com.harmoniasuite.source.application.port.SourceUploadSessionRepository;
import com.harmoniasuite.source.application.port.SourceUploadStagingStorage;
import com.harmoniasuite.source.domain.SourceArtifact;
import com.harmoniasuite.source.domain.SourceArtifactCompression;
import com.harmoniasuite.source.domain.SourceSnapshot;
import com.harmoniasuite.source.domain.SourceUploadErrorCode;
import com.harmoniasuite.source.domain.SourceUploadState;
import com.harmoniasuite.source.domain.TransportEncoding;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Application state-machine processor for one queued upload. */
public final class SourceIngestionProcessor {

    private final SourceUploadSessionRepository sessions;
    private final SourceUploadStagingStorage staging;
    private final SourceSnapshotImportService importer;
    private final SourceArtifactRepository artifacts;
    private final SourceArtifactStorage artifactStorage;
    private final SourceIngestionPropertiesView limits;
    private final Clock clock;

    public SourceIngestionProcessor(SourceUploadSessionRepository sessions,
                                    SourceUploadStagingStorage staging,
                                    SourceSnapshotImportService importer,
                                    SourceArtifactRepository artifacts,
                                    SourceArtifactStorage artifactStorage,
                                    SourceIngestionPropertiesView limits,
                                    Clock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.staging = Objects.requireNonNull(staging, "staging");
        this.importer = Objects.requireNonNull(importer, "importer");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.artifactStorage = Objects.requireNonNull(artifactStorage, "artifactStorage");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void processNow(UUID uploadId) {
        synchronized (UploadSessionLocks.forUpload(uploadId)) {
            SourceUploadSession session = sessions.find(uploadId).orElse(null);
            if (session == null || session.state() != SourceUploadState.QUEUED) return;
            if (!sessions.transition(uploadId, SourceUploadState.QUEUED,
                    SourceUploadState.VERIFYING)) return;
            try {
                process(session);
            } catch (SourceUploadFailure failure) {
                sessions.fail(uploadId, failure.code(), failure.getMessage());
            } catch (Exception exception) {
                sessions.fail(uploadId, SourceUploadErrorCode.INTERNAL_FAILURE,
                        "source ingestion failed");
            }
        }
    }

    private void process(SourceUploadSession session) {
        PreparedSourceUpload prepared;
        try {
            if (!staging.exists(session.uploadId())) {
                throw new SourceUploadFailure(SourceUploadErrorCode.STAGING_MISSING,
                        "managed upload staging is missing");
            }
            prepared = staging.prepareForIngestion(session.uploadId(), session.transportEncoding(),
                    session.uploadSize(), session.uncompressedSize(), limits.maxUploadBytes(),
                    limits.maxHxsBytes());
        } catch (SourceUploadFailure failure) {
            throw failure;
        } catch (RuntimeException exception) {
            throw new SourceUploadFailure(SourceUploadErrorCode.DECOMPRESSION_FAILED,
                    "uploaded source payload could not be prepared", exception);
        }
        if (!sessions.setTransportHash(session.uploadId(), prepared.transportHash())) return;

        SourceSnapshotImportService.VerifiedHxs verified;
        try {
            verified = importer.verify(prepared.hxsPath());
        } catch (RuntimeException exception) {
            throw new SourceUploadFailure(SourceUploadErrorCode.ATLAS_VERIFICATION_FAILED,
                    "Atlas could not verify the uploaded HXS", exception);
        }
        if (!session.metadata().equals(verified.metadata())) {
            throw new SourceUploadFailure(SourceUploadErrorCode.METADATA_MISMATCH,
                    "uploaded metadata does not match the trusted Atlas inspection");
        }
        if (!sessions.transition(session.uploadId(), SourceUploadState.VERIFYING,
                SourceUploadState.MATERIALIZING)) return;

        SourceSnapshot snapshot;
        try {
            snapshot = importer.importVerified(verified);
        } catch (RuntimeException exception) {
            throw new SourceUploadFailure(SourceUploadErrorCode.MATERIALIZATION_FAILED,
                    "verified HXS could not be materialized", exception);
        }
        sessions.setSnapshotDbId(session.uploadId(), snapshot.id());
        if (!sessions.transition(session.uploadId(), SourceUploadState.MATERIALIZING,
                SourceUploadState.STORING)) return;

        try {
            StoredSourceArtifact stored = session.transportEncoding() == TransportEncoding.ZSTD
                    ? artifactStorage.storeCompressed(prepared.transportPath(), verified.metadata().snapshotId(),
                    prepared.hxsSize(), prepared.hxsHash())
                    : artifactStorage.storeHxs(prepared.hxsPath(), verified.metadata().snapshotId(),
                    prepared.hxsSize(), prepared.hxsHash(), limits.zstdLevel());
            artifacts.register(new SourceArtifact(0, snapshot.id(), stored.storageKey(),
                    SourceArtifactCompression.ZSTD, stored.uncompressedSize(), stored.compressedSize(),
                    stored.hxsFileHash(), stored.artifactHash(), clock.instant()));
        } catch (IOException | RuntimeException exception) {
            throw new SourceUploadFailure(SourceUploadErrorCode.ARTIFACT_STORAGE_FAILED,
                    "verified artifact could not be stored", exception);
        }
        if (sessions.transition(session.uploadId(), SourceUploadState.STORING,
                SourceUploadState.COMPLETED)) {
            try { staging.delete(session.uploadId()); }
            catch (IOException ignored) { }
        }
    }

}
