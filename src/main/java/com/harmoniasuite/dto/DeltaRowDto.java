package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeltaRowDto(
        String cellId,
        String filePath,
        String source,
        String translation,
        String status) {
}
