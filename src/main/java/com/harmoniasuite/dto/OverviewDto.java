package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OverviewDto(
        String id,
        String name,
        String inputRoot,
        String outputDir,
        String sourceLocale,
        String targetLocale,
        String createdAt,
        String updatedAt,
        SummaryDto summary) {
}
