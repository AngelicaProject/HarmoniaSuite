package com.harmoniasuite.source.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Passive request DTO for untrusted snapshot metadata. */
public record SourceSnapshotPreflightRequest(
        @JsonProperty("hxs_version") @NotNull @Min(1) @Max(1) Integer hxsVersion,
        @JsonProperty("game_version") @NotBlank String gameVersion,
        @NotBlank String language,
        @NotBlank @Pattern(regexp = "full") String scope,
        @NotBlank @Pattern(regexp = "sha256:[0-9a-f]{64}") String snapshotId,
        @NotBlank @Pattern(regexp = "sha256:[0-9a-f]{64}") String contentId,
        @JsonProperty("extractor_version") @NotBlank String extractorVersion,
        @JsonProperty("lumina_version") @NotBlank String luminaVersion,
        @JsonProperty("sheet_count") @NotNull @Min(0) Long sheetCount,
        @JsonProperty("row_count") @NotNull @Min(0) Long rowCount,
        @JsonProperty("string_cell_count") @NotNull @Min(0) Long stringCellCount) {
}
