package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Result of checking untrusted client metadata against the trusted source registry. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SourceSnapshotPreflightResponse(
        String status,
        String snapshotId,
        SourceSnapshotDto snapshot) {

    public static SourceSnapshotPreflightResponse uploadRequired(String snapshotId) {
        return new SourceSnapshotPreflightResponse("UPLOAD_REQUIRED", snapshotId, null);
    }

    public static SourceSnapshotPreflightResponse available(SourceSnapshotDto snapshot) {
        return new SourceSnapshotPreflightResponse("AVAILABLE", null, snapshot);
    }
}
