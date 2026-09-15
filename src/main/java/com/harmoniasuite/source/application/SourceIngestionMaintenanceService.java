package com.harmoniasuite.source.application;

import com.harmoniasuite.source.application.port.SourceIngestionDispatcher;
import com.harmoniasuite.source.application.port.SourceUploadSessionRepository;
import com.harmoniasuite.source.application.port.SourceUploadStagingStorage;
import com.harmoniasuite.source.application.model.SourceUploadSession;
import com.harmoniasuite.source.domain.SourceUploadErrorCode;
import com.harmoniasuite.source.domain.SourceUploadState;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Recovery and cleanup use cases kept separate from the asynchronous processor. */
public final class SourceIngestionMaintenanceService {

    private final SourceUploadSessionRepository sessions;
    private final SourceUploadStagingStorage staging;
    private final SourceIngestionDispatcher dispatcher;
    private final long staleUploadHours;
    private final Clock clock;

    public SourceIngestionMaintenanceService(SourceUploadSessionRepository sessions,
                                             SourceUploadStagingStorage staging,
                                             SourceIngestionDispatcher dispatcher,
                                             long staleUploadHours, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.staging = Objects.requireNonNull(staging, "staging");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.staleUploadHours = staleUploadHours;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void recoverPersistedProcessing() {
        for (SourceUploadSession candidate : sessions.findProcessing()) {
            synchronized (UploadSessionLocks.forUpload(candidate.uploadId())) {
                SourceUploadSession session = sessions.find(candidate.uploadId()).orElse(null);
                if (session == null || !session.state().processing()) continue;
                if (!staging.exists(session.uploadId())) {
                    sessions.fail(session.uploadId(), SourceUploadErrorCode.STAGING_MISSING,
                            "managed upload staging is missing");
                    continue;
                }
                if (session.state() != SourceUploadState.QUEUED
                        && !sessions.requeueProcessing(session.uploadId())) continue;
                dispatcher.dispatch(session.uploadId());
            }
        }
    }

    public void recoverQueued() {
        for (SourceUploadSession candidate : sessions.findQueued()) {
            synchronized (UploadSessionLocks.forUpload(candidate.uploadId())) {
                SourceUploadSession session = sessions.find(candidate.uploadId()).orElse(null);
                if (session != null && session.state() == SourceUploadState.QUEUED) {
                    dispatcher.dispatch(session.uploadId());
                }
            }
        }
    }

    public void cleanupExpired() {
        var cutoff = clock.instant().minus(Duration.ofHours(staleUploadHours));
        for (SourceUploadState state : List.of(SourceUploadState.UPLOADING, SourceUploadState.FAILED)) {
            for (SourceUploadSession candidate : sessions.findExpired(state, cutoff)) {
                synchronized (UploadSessionLocks.forUpload(candidate.uploadId())) {
                    SourceUploadSession current = sessions.find(candidate.uploadId()).orElse(null);
                    if (current == null || current.state() != state || !current.updatedAt().isBefore(cutoff)) continue;
                    try {
                        staging.delete(current.uploadId());
                        sessions.delete(current.uploadId());
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }
}
