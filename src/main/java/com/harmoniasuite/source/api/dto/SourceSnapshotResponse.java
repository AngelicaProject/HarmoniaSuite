package com.harmoniasuite.source.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Passive response DTO for trusted source snapshot metadata. */
public record SourceSnapshotResponse(
        @JsonProperty("snapshot_id") String snapshotId,
        @JsonProperty("content_id") String contentId,
        @JsonProperty("hxs_version") int hxsVersion,
        @JsonProperty("game_version") String gameVersion,
        String language,
        String scope,
        @JsonProperty("extractor_version") String extractorVersion,
        @JsonProperty("lumina_version") String luminaVersion,
        @JsonProperty("sheet_count") long sheetCount,
        @JsonProperty("row_count") long rowCount,
        @JsonProperty("string_cell_count") long stringCellCount) {
}
