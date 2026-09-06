package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeltaImportRequest(
        String author,
        List<String> filesAllowlist,
        String maxStatus,
        String sourcesFp,
        String gameVersion,
        List<DeltaRowDto> rows) {
}
