package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PendingByFileDto(
        Map<String, Long> files) {
}
