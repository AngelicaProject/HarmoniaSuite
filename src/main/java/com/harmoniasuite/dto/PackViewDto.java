package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.harmoniasuite.domain.PackMeta;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PackViewDto(
        PackMeta pack,
        Map<String, Object> manifest,
        List<String> errors) {
}
