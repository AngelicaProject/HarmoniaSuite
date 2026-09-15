package com.harmoniasuite.source.upload;

import java.util.List;
import java.util.Optional;

public interface SourceUploadSessionStore {

    SourceUploadSession create(SourceUploadSession session);

    Optional<SourceUploadSession> find(String uploadId);

    List<SourceUploadSession> findProcessing();

    List<SourceUploadSession> findExpired(SourceUploadState state, long cutoffMs);

    boolean transition(String uploadId, SourceUploadState expected, SourceUploadState target);

    boolean requeueProcessing(String uploadId);

    boolean updateReceivedBytes(String uploadId, long receivedBytes);

    boolean setTransportHash(String uploadId, byte[] transportHash);

    boolean setSnapshotDbId(String uploadId, long snapshotDbId);

    boolean fail(String uploadId, String errorCode, String errorMessage);

    void delete(String uploadId);
}
