package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeltaHeaderDto(
        String sourcesFp,
        String gameVersion,
        String author,
        String exportedAt) {
}
