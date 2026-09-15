package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Untrusted metadata and transport declaration used to create a resumable upload. */
public record SourceSnapshotUploadRequest(
        @JsonAlias("hxsVersion") @NotNull Integer hxsVersion,
        @JsonAlias("gameVersion") @NotBlank String gameVersion,
        @NotBlank String language,
        @NotBlank String scope,
        @JsonAlias("snapshotId") @NotBlank @Pattern(regexp = "sha256:[0-9a-f]{64}") String snapshotId,
        @JsonAlias("contentId") @NotBlank @Pattern(regexp = "sha256:[0-9a-f]{64}") String contentId,
        @JsonAlias("extractorVersion") @NotBlank String extractorVersion,
        @JsonAlias("luminaVersion") @NotBlank String luminaVersion,
        @JsonAlias("sheetCount") @NotNull @Min(0) Long sheetCount,
        @JsonAlias("rowCount") @NotNull @Min(0) Long rowCount,
        @JsonAlias("stringCellCount") @NotNull @Min(0) Long stringCellCount,
        @JsonAlias("transportEncoding") @NotBlank String transportEncoding,
        @JsonAlias("uploadSize") @NotNull @Min(1) Long uploadSize,
        @JsonAlias("uncompressedSize") @NotNull @Min(1) Long uncompressedSize) {

    public SourceSnapshotPreflightRequest preflight() {
        return new SourceSnapshotPreflightRequest(hxsVersion, gameVersion, language, scope,
                snapshotId, contentId, extractorVersion, luminaVersion, sheetCount, rowCount,
                stringCellCount);
    }
}
