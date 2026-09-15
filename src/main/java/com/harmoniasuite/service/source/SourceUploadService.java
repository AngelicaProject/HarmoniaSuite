package com.harmoniasuite.service.source;

import com.harmoniasuite.config.HarmoniaProperties;
import com.harmoniasuite.dto.SourceSnapshotUploadRequest;
import com.harmoniasuite.dto.SourceSnapshotPreflightResponse;
import com.harmoniasuite.dto.SourceSnapshotDto;
import com.harmoniasuite.dto.SourceUploadResponse;
import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import com.harmoniasuite.exception.HarmoniaSuiteConflictException;
import com.harmoniasuite.exception.HarmoniaSuiteNotFoundException;
import com.harmoniasuite.exception.SourceUploadTooLargeException;
import com.harmoniasuite.source.artifact.SourceArtifactPath;
import com.harmoniasuite.source.store.SourceSnapshot;
import com.harmoniasuite.source.store.SourceSnapshotStore;
import com.harmoniasuite.source.upload.SourceUploadClaim;
import com.harmoniasuite.source.upload.SourceIngestionQueue;
import com.harmoniasuite.source.upload.SourceUploadSession;
import com.harmoniasuite.source.upload.SourceUploadSessionStore;
import com.harmoniasuite.source.upload.SourceUploadState;
import com.harmoniasuite.source.upload.TransportEncoding;
import com.harmoniasuite.source.upload.UploadOffsetConflictException;
import com.harmoniasuite.source.upload.UploadSessionLocks;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** HTTP-facing service for server-managed resumable HXS uploads. */
@Service
public final class SourceUploadService {

    private static final int BUFFER_SIZE = 64 * 1024;

    private final SourceSnapshotService snapshots;
    private final SourceSnapshotStore snapshotStore;
    private final SourceUploadSessionStore sessions;
    private final SourceArtifactPath paths;
    private final HarmoniaProperties properties;
    private final SourceIngestionQueue worker;

    public SourceUploadService(SourceSnapshotService snapshots, SourceSnapshotStore snapshotStore,
                               SourceUploadSessionStore sessions, SourceArtifactPath paths,
                               HarmoniaProperties properties, SourceIngestionQueue worker) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.paths = Objects.requireNonNull(paths, "paths");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.worker = Objects.requireNonNull(worker, "worker");
    }

    public SourceUploadResponse create(SourceSnapshotUploadRequest request) {
        if (request == null) {
            throw new HarmoniaSuiteBadRequestException("upload request must not be null");
        }
        TransportEncoding encoding;
        try {
            encoding = TransportEncoding.parse(request.transportEncoding());
        } catch (IllegalArgumentException exception) {
            throw new HarmoniaSuiteBadRequestException(exception.getMessage());
        }
        validateSizes(request, encoding);

        SourceSnapshotPreflightResponse preflight = snapshots.preflight(request.preflight());
        if ("AVAILABLE".equals(preflight.status())) {
            return SourceUploadResponse.available(preflight.snapshot());
        }

        String uploadId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        SourceUploadSession session = new SourceUploadSession(uploadId, SourceUploadState.UPLOADING,
                encoding, request.uploadSize(), request.uncompressedSize(), 0,
                SourceUploadClaim.from(request.preflight()), null, null, null, null, now, now);
        try {
            paths.createStagingDirectory(uploadId);
            sessions.create(session);
        } catch (IOException | RuntimeException exception) {
            deleteTreeQuietly(paths.uploadDirectory(uploadId));
            throw new IllegalStateException("source upload session could not be created", exception);
        }
        return SourceUploadResponse.from(session, null);
    }

    public SourceUploadResponse status(String uploadId) {
        SourceUploadSession session = findSession(uploadId);
        if (session.state() == SourceUploadState.UPLOADING) {
            synchronized (UploadSessionLocks.forUpload(uploadId)) {
                session = reconcile(session);
            }
        }
        SourceSnapshotDto snapshot = trustedSnapshot(session);
        return SourceUploadResponse.from(session, snapshot);
    }

    public SourceUploadResponse append(String uploadId, Long offset, Long contentLength,
                                       InputStream requestBody) {
        if (offset == null) {
            throw new HarmoniaSuiteBadRequestException("Upload-Offset is required");
        }
        if (contentLength == null) {
            throw new HarmoniaSuiteBadRequestException("Content-Length is required");
        }
        if (requestBody == null) {
            throw new HarmoniaSuiteBadRequestException("upload body is required");
        }
        if (offset < 0) {
            throw new HarmoniaSuiteBadRequestException("Upload-Offset must be non-negative");
        }
        if (contentLength <= 0) {
            throw new HarmoniaSuiteBadRequestException("chunk size must be positive");
        }

        synchronized (UploadSessionLocks.forUpload(uploadId)) {
            SourceUploadSession session = findSession(uploadId);
            requireState(session, SourceUploadState.UPLOADING);
            session = reconcile(session);
            if (offset != session.receivedBytes()) {
                throw new UploadOffsetConflictException(session.receivedBytes());
            }
            long maxChunk = properties.getSourceIngestion().getMaxChunkBytes();
            if (contentLength > maxChunk) {
                throw new SourceUploadTooLargeException("chunk exceeds configured limit");
            }
            if (contentLength > session.uploadSize() - session.receivedBytes()) {
                throw new SourceUploadTooLargeException("chunk exceeds declared upload size");
            }

            Path directory = requireStagingDirectory(uploadId);
            Path payload = paths.payloadPath(uploadId);
            Path temporaryChunk = paths.chunkTempPath(uploadId);
            try {
                deleteManagedFileIfPresent(temporaryChunk);
                long copied = copyBounded(requestBody, temporaryChunk, maxChunk);
                if (copied != contentLength) {
                    throw new HarmoniaSuiteBadRequestException(
                            "request body does not match Content-Length");
                }
                if (copied > session.uploadSize() - session.receivedBytes()) {
                    throw new SourceUploadTooLargeException("chunk exceeds declared upload size");
                }
                appendFile(temporaryChunk, payload);
                long newOffset = Files.size(payload);
                if (newOffset > session.uploadSize()) {
                    throw new SourceUploadTooLargeException("upload exceeds declared size");
                }
                if (!sessions.updateReceivedBytes(uploadId, newOffset)) {
                    throw new HarmoniaSuiteConflictException("upload is no longer accepting chunks");
                }
                return SourceUploadResponse.from(findSession(uploadId), null);
            } catch (IOException exception) {
                throw new IllegalStateException("upload chunk could not be durably stored", exception);
            } finally {
                deleteManagedFileIfPresent(temporaryChunk);
            }
        }
    }

    public SourceUploadResponse complete(String uploadId) {
        synchronized (UploadSessionLocks.forUpload(uploadId)) {
            SourceUploadSession session = findSession(uploadId);
            requireState(session, SourceUploadState.UPLOADING);
            session = reconcile(session);
            if (session.receivedBytes() != session.uploadSize()) {
                throw new HarmoniaSuiteConflictException("upload is incomplete; expected offset "
                        + session.receivedBytes());
            }
            if (!sessions.transition(uploadId, SourceUploadState.UPLOADING,
                    SourceUploadState.QUEUED)) {
                throw new HarmoniaSuiteConflictException("upload is no longer accepting completion");
            }
            SourceUploadSession queued = findSession(uploadId);
            worker.enqueue(uploadId);
            return SourceUploadResponse.queued(queued);
        }
    }

    public void cancel(String uploadId) {
        synchronized (UploadSessionLocks.forUpload(uploadId)) {
            SourceUploadSession session = findSession(uploadId);
            if (session.state() != SourceUploadState.UPLOADING
                    && session.state() != SourceUploadState.FAILED) {
                throw new HarmoniaSuiteConflictException("upload cannot be cancelled in its current state");
            }
            try {
                deleteTree(paths.uploadDirectory(uploadId));
                sessions.delete(uploadId);
            } catch (IOException exception) {
                throw new IllegalStateException("upload staging could not be deleted", exception);
            }
        }
    }

    private SourceUploadSession findSession(String uploadId) {
        if (uploadId == null || uploadId.isBlank()) {
            throw new HarmoniaSuiteBadRequestException("uploadId must not be blank");
        }
        return sessions.find(uploadId).orElseThrow(
                () -> new HarmoniaSuiteNotFoundException("source upload was not found"));
    }

    private SourceUploadSession reconcile(SourceUploadSession session) {
        Path payload = paths.payloadPath(session.uploadId());
        long actual;
        try {
            if (!Files.exists(payload, LinkOption.NOFOLLOW_LINKS)) {
                actual = 0;
            } else {
                SourceArtifactPath.requireRegularFile(payload, "uploaded payload");
                actual = Files.size(payload);
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("upload staging could not be inspected", exception);
        }
        if (actual > session.uploadSize()) {
            throw new SourceUploadTooLargeException("staging file exceeds declared upload size");
        }
        if (actual != session.receivedBytes()) {
            if (!sessions.updateReceivedBytes(session.uploadId(), actual)) {
                return findSession(session.uploadId());
            }
            return findSession(session.uploadId());
        }
        return session;
    }

    private SourceSnapshotDto trustedSnapshot(SourceUploadSession session) {
        if (session.state() != SourceUploadState.COMPLETED) {
            return null;
        }
        SourceSnapshot snapshot = snapshotStore.findBySnapshotId(session.claim().snapshotId())
                .orElseThrow(() -> new IllegalStateException(
                        "completed upload has no canonical source snapshot"));
        return SourceSnapshotDto.from(snapshot);
    }

    private Path requireStagingDirectory(String uploadId) {
        Path directory = paths.uploadDirectory(uploadId);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) {
            throw new HarmoniaSuiteNotFoundException("source upload staging was not found");
        }
        return directory;
    }

    private static void requireState(SourceUploadSession session, SourceUploadState expected) {
        if (session.state() != expected) {
            throw new HarmoniaSuiteConflictException("upload is not in the required state");
        }
    }

    private void validateSizes(SourceSnapshotUploadRequest request, TransportEncoding encoding) {
        long maxUpload = properties.getSourceIngestion().getMaxUploadBytes();
        long maxHxs = properties.getSourceIngestion().getMaxHxsBytes();
        if (request.uploadSize() == null || request.uncompressedSize() == null
                || request.uploadSize() <= 0 || request.uncompressedSize() <= 0) {
            throw new HarmoniaSuiteBadRequestException("upload sizes must be positive");
        }
        if (request.uploadSize() > maxUpload) {
            throw new SourceUploadTooLargeException("upload exceeds configured limit");
        }
        if (request.uncompressedSize() > maxHxs) {
            throw new SourceUploadTooLargeException("HXS exceeds configured limit");
        }
        if (encoding == TransportEncoding.IDENTITY
                && !request.uploadSize().equals(request.uncompressedSize())) {
            throw new HarmoniaSuiteBadRequestException(
                    "identity uploadSize must equal uncompressedSize");
        }
    }

    private static long copyBounded(InputStream input, Path target, long maximum) throws IOException {
        long total = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total = Math.addExact(total, read);
                output.write(buffer, 0, read);
                if (total > maximum) {
                    throw new SourceUploadTooLargeException("chunk exceeds configured limit");
                }
            }
        } catch (ArithmeticException exception) {
            throw new SourceUploadTooLargeException("chunk size overflow");
        }
        return total;
    }

    private static void appendFile(Path source, Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            SourceArtifactPath.requireRegularFile(target, "uploaded payload");
        }
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(source);
             FileChannel output = FileChannel.open(target, StandardOpenOption.CREATE,
                     StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, read);
                while (bytes.hasRemaining()) {
                    output.write(bytes);
                }
            }
            output.force(true);
        }
    }

    private static void deleteManagedFileIfPresent(Path path) {
        try {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                SourceArtifactPath.requireRegularFile(path, "managed chunk file");
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("managed chunk file could not be deleted", exception);
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
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
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

    private static void deleteTreeQuietly(Path directory) {
        try {
            deleteTree(directory);
        } catch (IOException ignored) {
        }
    }

    private static final class DeleteTreeRuntimeException extends RuntimeException {
        private final IOException ioException;

        private DeleteTreeRuntimeException(IOException ioException) {
            this.ioException = ioException;
        }
    }
}
