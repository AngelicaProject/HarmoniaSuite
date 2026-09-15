package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.harmoniasuite.source.upload.SourceUploadSession;

/** Public upload lifecycle projection; managed paths are deliberately absent. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SourceUploadResponse(
        String status,
        String uploadId,
        String state,
        String transportEncoding,
        Long uploadSize,
        Long uncompressedSize,
        Long receivedBytes,
        Long nextOffset,
        String errorCode,
        String errorMessage,
        SourceSnapshotDto snapshot) {

    public static SourceUploadResponse available(SourceSnapshotDto snapshot) {
        return new SourceUploadResponse("AVAILABLE", null, null, null, null, null, null, null,
                null, null, snapshot);
    }

    public static SourceUploadResponse from(SourceUploadSession session,
                                            SourceSnapshotDto snapshot) {
        return new SourceUploadResponse(session.state().name(), session.uploadId(), session.state().name(),
                session.transportEncoding().wireValue(), session.uploadSize(),
                session.uncompressedSize(), session.receivedBytes(), session.receivedBytes(),
                session.errorCode(), session.errorMessage(), snapshot);
    }

    public static SourceUploadResponse queued(SourceUploadSession session) {
        return from(session, null);
    }
}
