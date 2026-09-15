package com.harmoniasuite.source.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateSourceUploadResponse(
        String status,
        SourceSnapshotResponse snapshot,
        SourceUploadResponse upload) {
}
