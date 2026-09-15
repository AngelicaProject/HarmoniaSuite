package com.harmoniasuite.source.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Passive response DTO for trusted source sheet metadata. */
public record SourceSheetResponse(
        String name,
        int variant,
        @JsonProperty("effective_language") String effectiveLanguage,
        @JsonProperty("column_count") long columnCount,
        @JsonProperty("row_count") long rowCount,
        String schemaHash,
        String technicalHash,
        String stringHash,
        String contentHash) {
}
