package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SourcePreviewDto(
        String file,
        long rows,
        List<Integer> stringColumns,
        long translatable,
        List<List<String>> preview,
        List<List<String>> head,
        List<List<String>> data,
        Boolean truncated) {
}
