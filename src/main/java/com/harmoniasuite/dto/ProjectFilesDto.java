package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProjectFilesDto(
        List<FileStatsDto> files,
        long total,
        int offset,
        int limit,
        long needFiles,
        long readyFiles,
        SummaryDto summary) {
}
