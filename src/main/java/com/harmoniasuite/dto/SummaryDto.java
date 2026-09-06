package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SummaryDto(
        long entries,
        long files,
        long translated,
        long untranslated,
        Map<String, Long> byStatus,
        String outputDir) {
}
