package com.harmoniasuite.source.application.port;

import com.harmoniasuite.source.application.model.SourceUploadSession;
import com.harmoniasuite.source.domain.Sha256Digest;
import com.harmoniasuite.source.domain.SourceUploadErrorCode;
import com.harmoniasuite.source.domain.SourceUploadState;
import java.util.List;
import java.util.Optional;

public interface SourceUploadSessionRepository {

    SourceUploadSession create(SourceUploadSession session);

    Optional<SourceUploadSession> find(java.util.UUID uploadId);

    List<SourceUploadSession> findProcessing();

    List<SourceUploadSession> findQueued();

    List<SourceUploadSession> findExpired(SourceUploadState state, java.time.Instant cutoff);

    boolean transition(java.util.UUID uploadId, SourceUploadState expected, SourceUploadState target);

    boolean requeueProcessing(java.util.UUID uploadId);

    boolean updateReceivedBytes(java.util.UUID uploadId, long receivedBytes);

    boolean setTransportHash(java.util.UUID uploadId, Sha256Digest transportHash);

    boolean setSnapshotDbId(java.util.UUID uploadId, long snapshotDbId);

    boolean fail(java.util.UUID uploadId, SourceUploadErrorCode errorCode, String errorMessage);

    void delete(java.util.UUID uploadId);
}
