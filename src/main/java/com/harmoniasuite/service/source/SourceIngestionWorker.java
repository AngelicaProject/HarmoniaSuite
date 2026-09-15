package com.harmoniasuite.service.source;

import com.harmoniasuite.config.HarmoniaProperties;
import com.harmoniasuite.source.artifact.SourceArtifact;
import com.harmoniasuite.source.artifact.SourceArtifactPath;
import com.harmoniasuite.source.artifact.SourceArtifactRegistry;
import com.harmoniasuite.source.artifact.SourceArtifactStore;
import com.harmoniasuite.source.artifact.SourceCompression;
import com.harmoniasuite.source.artifact.StoredSourceArtifact;
import com.harmoniasuite.source.atlas.AtlasException;
import com.harmoniasuite.source.atlas.AtlasInspection;
import com.harmoniasuite.source.store.SourceSnapshot;
import com.harmoniasuite.source.store.SourceSnapshotImportException;
import com.harmoniasuite.source.store.SourceSnapshotImporter;
import com.harmoniasuite.source.store.SourceSnapshotStore;
import com.harmoniasuite.source.upload.SourceUploadFailure;
import com.harmoniasuite.source.upload.SourceIngestionQueue;
import com.harmoniasuite.source.upload.SourceUploadSession;
import com.harmoniasuite.source.upload.SourceUploadSessionStore;
import com.harmoniasuite.source.upload.SourceUploadState;
import com.harmoniasuite.source.upload.TransportEncoding;
import com.harmoniasuite.source.upload.UploadSessionLocks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;

/** Dedicated asynchronous state-machine worker for canonical HXS ingestion. */
@Component
public final class SourceIngestionWorker implements SourceIngestionQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger(SourceIngestionWorker.class);

    private final SourceUploadSessionStore sessions;
    private final SourceArtifactPath paths;
    private final SourceSnapshotImporter importer;
    private final SourceSnapshotStore snapshots;
    private final SourceArtifactRegistry artifacts;
    private final SourceArtifactStore artifactStore;
    private final HarmoniaProperties properties;
    private final Executor executor;

    public SourceIngestionWorker(SourceUploadSessionStore sessions, SourceArtifactPath paths,
                                 SourceSnapshotImporter importer, SourceSnapshotStore snapshots,
                                 SourceArtifactRegistry artifacts, SourceArtifactStore artifactStore,
                                 HarmoniaProperties properties,
                                 @Qualifier("sourceIngestionExecutor") Executor executor) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.paths = Objects.requireNonNull(paths, "paths");
        this.importer = Objects.requireNonNull(importer, "importer");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public void enqueue(String uploadId) {
        try {
            executor.execute(() -> processNow(uploadId));
        } catch (RejectedExecutionException exception) {
            sessions.fail(uploadId, "PROCESSING_UNAVAILABLE", "source ingestion worker is unavailable");
        }
    }

    /** Synchronous seam for deterministic service and recovery tests. */
    public void processNow(String uploadId) {
        synchronized (UploadSessionLocks.forUpload(uploadId)) {
            SourceUploadSession session = sessions.find(uploadId).orElse(null);
            if (session == null || session.state() == SourceUploadState.COMPLETED
                    || session.state() == SourceUploadState.FAILED
                    || session.state() == SourceUploadState.UPLOADING) {
                return;
            }
            if (session.state().processing() && session.state() != SourceUploadState.QUEUED) {
                if (!sessions.requeueProcessing(uploadId)) {
                    return;
                }
                session = sessions.find(uploadId).orElse(null);
                if (session == null) {
                    return;
                }
            }
            if (session.state() == SourceUploadState.QUEUED
                    && !sessions.transition(uploadId, SourceUploadState.QUEUED,
                    SourceUploadState.VERIFYING)) {
                return;
            }

            try {
                process(session);
            } catch (SourceUploadFailure failure) {
                sessions.fail(uploadId, failure.code(), failure.getMessage());
                LOGGER.warn("source upload {} failed: {}", uploadId, failure.code());
            } catch (Exception exception) {
                sessions.fail(uploadId, "INTERNAL_FAILURE", "source ingestion failed");
                LOGGER.error("source upload {} failed", uploadId, exception);
            }
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        recoverProcessing();
        cleanupExpired();
    }

    @Scheduled(fixedDelay = 3_600_000L)
    public void scheduledMaintenance() {
        recoverProcessing();
        cleanupExpired();
    }

    private void process(SourceUploadSession session) {
        Path uploadDirectory = requireStagingDirectory(session.uploadId());
        Path payload = paths.payloadPath(session.uploadId());
        SourceArtifactPath.requireRegularFile(payload, "uploaded payload");

        SourceCompression.FileDigest transport = digestTransport(payload, session);
        if (!sessions.setTransportHash(session.uploadId(), transport.hash())) {
            return;
        }

        Path hxsPath = payload;
        SourceCompression.FileDigest hxsDigest;
        if (session.transportEncoding() == TransportEncoding.ZSTD) {
            Path temporary = paths.snapshotTempPath(session.uploadId());
            deleteManagedFileIfPresent(temporary);
            try {
                hxsDigest = SourceCompression.decompressExact(payload, temporary,
                        session.uncompressedSize(), properties.getSourceIngestion().getMaxHxsBytes());
                force(temporary);
                Path snapshotPath = paths.snapshotPath(session.uploadId());
                deleteManagedFileIfPresent(snapshotPath);
                moveAtomically(temporary, snapshotPath);
                hxsPath = snapshotPath;
            } catch (IOException exception) {
                throw new SourceUploadFailure(
                        exception.getMessage() != null && exception.getMessage().contains("exceeds")
                                ? "HXS_TOO_LARGE" : "DECOMPRESSION_FAILED",
                        "uploaded zstd payload could not be decoded", exception);
            } finally {
                deleteManagedFileIfPresent(temporary);
            }
        } else {
            if (session.uploadSize() != session.uncompressedSize()) {
                throw new SourceUploadFailure("SIZE_MISMATCH",
                        "identity upload sizes do not match");
            }
            hxsDigest = digestHxs(payload, session.uncompressedSize());
        }

        SourceSnapshotImporter.VerifiedHxs verified;
        try {
            verified = importer.verify(hxsPath);
        } catch (AtlasException exception) {
            throw new SourceUploadFailure("ATLAS_VERIFICATION_FAILED",
                    "Atlas could not verify the uploaded HXS", exception);
        }
        assertClaimMatches(session, verified.inspection());

        if (!sessions.transition(session.uploadId(), SourceUploadState.VERIFYING,
                SourceUploadState.MATERIALIZING)) {
            return;
        }
        SourceSnapshot snapshot;
        try {
            snapshot = importer.importVerified(verified);
        } catch (RuntimeException exception) {
            if (exception instanceof SourceUploadFailure failure) {
                throw failure;
            }
            throw new SourceUploadFailure("MATERIALIZATION_FAILED",
                    "verified HXS could not be materialized", exception);
        }
        sessions.setSnapshotDbId(session.uploadId(), snapshot.id());

        if (!sessions.transition(session.uploadId(), SourceUploadState.MATERIALIZING,
                SourceUploadState.STORING)) {
            return;
        }
        StoredSourceArtifact stored;
        try {
            stored = session.transportEncoding() == TransportEncoding.ZSTD
                    ? artifactStore.storeCompressed(payload, verified.inspection().snapshotId(),
                    hxsDigest.size(), hxsDigest.hash())
                    : artifactStore.storeHxs(hxsPath, verified.inspection().snapshotId(),
                    hxsDigest.size(), hxsDigest.hash(),
                    properties.getSourceIngestion().getZstdLevel());
            artifacts.register(new SourceArtifact(0, snapshot.id(), stored.storageKey(), "zstd",
                    stored.uncompressedSize(), stored.compressedSize(), stored.hxsFileHash(),
                    stored.artifactHash(), System.currentTimeMillis()));
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof SourceUploadFailure failure) {
                throw failure;
            }
            throw new SourceUploadFailure("ARTIFACT_STORAGE_FAILED",
                    "verified artifact could not be stored", exception);
        }

        if (sessions.transition(session.uploadId(), SourceUploadState.STORING,
                SourceUploadState.COMPLETED)) {
            try {
                deleteTree(uploadDirectory);
            } catch (IOException exception) {
                LOGGER.warn("source upload {} completed but staging cleanup failed",
                        session.uploadId(), exception);
            }
        }
    }

    private SourceCompression.FileDigest digestTransport(Path payload, SourceUploadSession session) {
        try {
            SourceCompression.FileDigest digest = SourceCompression.digestFile(payload,
                    properties.getSourceIngestion().getMaxUploadBytes());
            if (digest.size() != session.uploadSize()) {
                throw new SourceUploadFailure("SIZE_MISMATCH",
                        "uploaded transport size does not match declaration");
            }
            return digest;
        } catch (SourceUploadFailure failure) {
            throw failure;
        } catch (IOException exception) {
            throw new SourceUploadFailure("TRANSPORT_HASH_FAILURE",
                    "uploaded transport hash could not be computed", exception);
        }
    }

    private SourceCompression.FileDigest digestHxs(Path hxsPath, long expectedSize) {
        try {
            SourceCompression.FileDigest digest = SourceCompression.digestFile(hxsPath,
                    properties.getSourceIngestion().getMaxHxsBytes());
            if (digest.size() != expectedSize) {
                throw new SourceUploadFailure("SIZE_MISMATCH",
                        "identity HXS size does not match declaration");
            }
            return digest;
        } catch (SourceUploadFailure failure) {
            throw failure;
        } catch (IOException exception) {
            throw new SourceUploadFailure("HXS_TOO_LARGE", "uploaded HXS could not be read", exception);
        }
    }

    private void assertClaimMatches(SourceUploadSession session, AtlasInspection inspection) {
        if (session.claim().hxsVersion() != inspection.hxsVersion()
                || !session.claim().gameVersion().equals(inspection.gameVersion())
                || !session.claim().language().equals(inspection.language())
                || !session.claim().scope().equals(inspection.scope())
                || !session.claim().snapshotId().equals(inspection.snapshotId())
                || !session.claim().contentId().equals(inspection.contentId())
                || !session.claim().extractorVersion().equals(inspection.extractorVersion())
                || !session.claim().luminaVersion().equals(inspection.luminaVersion())
                || session.claim().sheetCount() != inspection.sheetCount()
                || session.claim().rowCount() != inspection.rowCount()
                || session.claim().stringCellCount() != inspection.stringCellCount()) {
            throw new SourceUploadFailure("METADATA_MISMATCH",
                    "uploaded metadata does not match the trusted Atlas inspection");
        }
    }

    private void recoverProcessing() {
        for (SourceUploadSession session : sessions.findProcessing()) {
            try {
                Path directory = paths.uploadDirectory(session.uploadId());
                Path payload = paths.payloadPath(session.uploadId());
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                        || !Files.isRegularFile(payload, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(payload)) {
                    sessions.fail(session.uploadId(), "STAGING_MISSING",
                            "managed upload staging is missing");
                    continue;
                }
                if (session.state() != SourceUploadState.QUEUED) {
                    sessions.requeueProcessing(session.uploadId());
                }
                enqueue(session.uploadId());
            } catch (RuntimeException exception) {
                sessions.fail(session.uploadId(), "STAGING_MISSING",
                        "managed upload staging is unavailable");
            }
        }
    }

    private void cleanupExpired() {
        long cutoff = System.currentTimeMillis()
                - properties.getSourceIngestion().getStaleUploadHours() * 3_600_000L;
        for (SourceUploadState state : List.of(SourceUploadState.UPLOADING, SourceUploadState.FAILED)) {
            for (SourceUploadSession session : sessions.findExpired(state, cutoff)) {
                try {
                    deleteTree(paths.uploadDirectory(session.uploadId()));
                    sessions.delete(session.uploadId());
                } catch (RuntimeException | IOException exception) {
                    LOGGER.warn("could not clean source upload {}", session.uploadId());
                }
            }
        }
    }

    private Path requireStagingDirectory(String uploadId) {
        Path directory = paths.uploadDirectory(uploadId);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) {
            throw new SourceUploadFailure("STAGING_MISSING", "managed upload staging is missing");
        }
        return directory;
    }

    private static void deleteManagedFileIfPresent(Path path) {
        try {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                SourceArtifactPath.requireRegularFile(path, "managed temporary file");
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            throw new SourceUploadFailure("STAGING_CLEANUP_FAILED",
                    "managed temporary file could not be replaced", exception);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void force(Path path) throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(path, StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("managed staging directory is not a directory");
        }
        try (var stream = Files.walk(directory)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new DeleteTreeRuntimeException(exception);
                }
            });
        } catch (DeleteTreeRuntimeException exception) {
            throw exception.ioException;
        }
    }

    private static final class DeleteTreeRuntimeException extends RuntimeException {
        private final IOException ioException;

        private DeleteTreeRuntimeException(IOException ioException) {
            this.ioException = ioException;
        }
    }
}
