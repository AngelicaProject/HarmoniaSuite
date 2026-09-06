package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeltaImportResultDto(
        int applied,
        int noop,
        List<DeltaSkippedDto> skipped,
        List<DeltaConflictDto> conflicts,
        List<DeltaWarningDto> warnings,
        SummaryDto summary) {
}
