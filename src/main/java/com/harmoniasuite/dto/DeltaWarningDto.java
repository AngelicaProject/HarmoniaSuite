package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeltaWarningDto(
        String cellId,
        String filePath,
        List<String> warnings) {
}
