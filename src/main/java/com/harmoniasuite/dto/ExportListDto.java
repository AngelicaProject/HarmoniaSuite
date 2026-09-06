package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExportListDto(
        List<ExportFileDto> files,
        boolean manifest) {
}
