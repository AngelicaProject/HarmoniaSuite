package com.harmoniasuite.source.application;

import com.harmoniasuite.source.application.command.CreateSourceUploadCommand;
import com.harmoniasuite.source.application.exception.HarmoniaSuiteBadRequestException;
import com.harmoniasuite.source.application.exception.HarmoniaSuiteConflictException;
import com.harmoniasuite.source.application.exception.HarmoniaSuiteNotFoundException;
import com.harmoniasuite.source.application.exception.SourceUploadTooLargeException;
import com.harmoniasuite.source.application.model.SourceIngestionPropertiesView;
import com.harmoniasuite.source.application.model.SourceUploadCreationResult;
import com.harmoniasuite.source.application.model.SourceUploadSession;
import com.harmoniasuite.source.application.port.SourceIngestionDispatcher;
import com.harmoniasuite.source.application.port.SourceUploadSessionRepository;
import com.harmoniasuite.source.application.port.SourceUploadStagingStorage;
import com.harmoniasuite.source.domain.SourceSnapshotAvailability;
import com.harmoniasuite.source.domain.SourceUploadCreationStatus;
import com.harmoniasuite.source.domain.SourceUploadState;
import com.harmoniasuite.source.domain.TransportEncoding;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Application coordinator for resumable uploads; all filesystem operations stay behind a port. */
public final class SourceUploadService {

    private final SourceSnapshotService snapshots;
    private final SourceUploadSessionRepository sessions;
    private final SourceUploadStagingStorage staging;
    private final SourceIngestionDispatcher dispatcher;
    private final SourceIngestionPropertiesView limits;
    private final Clock clock;

    public SourceUploadService(SourceSnapshotService snapshots,
                               SourceUploadSessionRepository sessions,
                               SourceUploadStagingStorage staging,
                               SourceIngestionPropertiesView limits,
                               SourceIngestionDispatcher dispatcher,
                               Clock clock) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.staging = Objects.requireNonNull(staging, "staging");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public SourceUploadCreationResult create(CreateSourceUploadCommand command) {
        Objects.requireNonNull(command, "command");
        TransportEncoding encoding;
        try {
            encoding = TransportEncoding.parse(command.transportEncoding());
        } catch (IllegalArgumentException exception) {
            throw new HarmoniaSuiteBadRequestException(exception.getMessage());
        }
        validateSizes(command, encoding);
        var preflight = snapshots.preflight(command.metadata());
        if (preflight.status() == SourceSnapshotAvailability.AVAILABLE) {
            return new SourceUploadCreationResult(SourceUploadCreationStatus.AVAILABLE,
                    preflight.snapshot(), null);
        }

        UUID uploadId = UUID.randomUUID();
        Instant now = clock.instant();
        SourceUploadSession session = new SourceUploadSession(uploadId, SourceUploadState.UPLOADING,
                encoding, command.uploadSize(), command.uncompressedSize(), 0,
                command.metadata(), null, null, null, null, now, now);
        try {
            staging.create(uploadId);
            sessions.create(session);
        } catch (IOException | RuntimeException exception) {
            try {
                staging.delete(uploadId);
            } catch (IOException ignored) {
            }
            throw new IllegalStateException("source upload session could not be created", exception);
        }
        return new SourceUploadCreationResult(SourceUploadCreationStatus.CREATED, null, session);
    }

    public SourceUploadSession status(UUID uploadId) {
        SourceUploadSession session = findSession(uploadId);
        if (session.state() == SourceUploadState.UPLOADING) {
            synchronized (UploadSessionLocks.forUpload(uploadId)) {
                session = reconcile(session);
            }
        }
        return session;
    }

    public SourceUploadSession append(UUID uploadId, Long offset, Long contentLength,
                                      InputStream body) {
        if (offset == null) {
            throw new HarmoniaSuiteBadRequestException("Upload-Offset is required");
        }
        if (contentLength == null) {
            throw new HarmoniaSuiteBadRequestException("Content-Length is required");
        }
        if (body == null) {
            throw new HarmoniaSuiteBadRequestException("upload body is required");
        }
        if (offset < 0 || contentLength <= 0) {
            throw new HarmoniaSuiteBadRequestException("upload offset and chunk size must be valid");
        }
        synchronized (UploadSessionLocks.forUpload(uploadId)) {
            SourceUploadSession session = findSession(uploadId);
            requireState(session, SourceUploadState.UPLOADING);
            session = reconcile(session);
            if (offset != session.receivedBytes()) {
                throw new com.harmoniasuite.source.application.exception.UploadOffsetConflictException(
                        session.receivedBytes());
            }
            if (contentLength > limits.maxChunkBytes()) {
                throw new SourceUploadTooLargeException("chunk exceeds configured limit");
            }
            if (contentLength > session.uploadSize() - session.receivedBytes()) {
                throw new SourceUploadTooLargeException("chunk exceeds declared upload size");
            }
            try {
                long newOffset = staging.append(uploadId, body, contentLength,
                        limits.maxChunkBytes(), session.uploadSize());
                if (!sessions.updateReceivedBytes(uploadId, newOffset)) {
                    throw new HarmoniaSuiteConflictException("upload is no longer accepting chunks");
                }
                return findSession(uploadId);
            } catch (IOException exception) {
                throw new IllegalStateException("upload chunk could not be durably stored", exception);
            }
        }
    }

    public SourceUploadSession complete(UUID uploadId) {
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
            dispatcher.dispatch(uploadId);
            return findSession(uploadId);
        }
    }

    public void cancel(UUID uploadId) {
        synchronized (UploadSessionLocks.forUpload(uploadId)) {
            SourceUploadSession session = findSession(uploadId);
            if (session.state() != SourceUploadState.UPLOADING
                    && session.state() != SourceUploadState.FAILED) {
                throw new HarmoniaSuiteConflictException("upload cannot be cancelled in its current state");
            }
            try {
                staging.delete(uploadId);
                sessions.delete(uploadId);
            } catch (IOException exception) {
                throw new IllegalStateException("upload staging could not be deleted", exception);
            }
        }
    }

    private SourceUploadSession findSession(UUID uploadId) {
        if (uploadId == null) {
            throw new HarmoniaSuiteBadRequestException("uploadId must not be blank");
        }
        return sessions.find(uploadId).orElseThrow(
                () -> new HarmoniaSuiteNotFoundException("source upload was not found"));
    }

    private SourceUploadSession reconcile(SourceUploadSession session) {
        long actual;
        try {
            actual = staging.receivedBytes(session.uploadId());
        } catch (IOException exception) {
            throw new IllegalStateException("upload staging could not be inspected", exception);
        }
        if (actual > session.uploadSize()) {
            throw new SourceUploadTooLargeException("staging file exceeds declared upload size");
        }
        if (actual != session.receivedBytes()) {
            sessions.updateReceivedBytes(session.uploadId(), actual);
            return findSession(session.uploadId());
        }
        return session;
    }

    private void validateSizes(CreateSourceUploadCommand command, TransportEncoding encoding) {
        if (command.uploadSize() <= 0 || command.uncompressedSize() <= 0) {
            throw new HarmoniaSuiteBadRequestException("upload sizes must be positive");
        }
        if (command.uploadSize() > limits.maxUploadBytes()) {
            throw new SourceUploadTooLargeException("upload exceeds configured limit");
        }
        if (command.uncompressedSize() > limits.maxHxsBytes()) {
            throw new SourceUploadTooLargeException("HXS exceeds configured limit");
        }
    }

    private static void requireState(SourceUploadSession session, SourceUploadState expected) {
        if (session.state() != expected) {
            throw new HarmoniaSuiteConflictException("upload is not in the required state");
        }
    }

}
