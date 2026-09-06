package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EntryDto(
        String uuid,
        String id,
        String source,
        String translation,
        String status,
        String file,
        String rowKey,
        int columnIndex,
        String columnName,
        int rowIndex,
        String createdAt,
        String updatedAt) {
}
