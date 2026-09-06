package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileTreeDto(
        List<FileStatsDto> files,
        long total) {
}
