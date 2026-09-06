package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobDto(
        String id,
        String action,
        String status,
        String output,
        Integer code) {
}
