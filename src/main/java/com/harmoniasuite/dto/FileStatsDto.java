package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileStatsDto(
        String path,
        long total,
        long translated,
        long untranslated) {
}
