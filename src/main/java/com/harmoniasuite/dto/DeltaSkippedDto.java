package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeltaSkippedDto(
        String cellId,
        String filePath,
        String reason,
        String detail) {
}
