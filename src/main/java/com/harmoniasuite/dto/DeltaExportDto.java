package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeltaExportDto(
        DeltaHeaderDto header,
        List<DeltaRowDto> rows,
        String nextSinceUpdatedAt,
        String nextSinceCellId,
        boolean complete) {
}
