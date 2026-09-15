package com.harmoniasuite.source.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SourceSnapshotPreflightResponse(
        String status,
        @JsonProperty("snapshot_id") String snapshotId,
        SourceSnapshotResponse snapshot) {
}
