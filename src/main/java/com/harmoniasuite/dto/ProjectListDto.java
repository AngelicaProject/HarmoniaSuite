package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProjectListDto(
        String id,
        String name,
        boolean exists,
        long files,
        long entries,
        long translated,
        String updatedAt,
        String error) {
}
