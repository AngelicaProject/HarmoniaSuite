package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeltaConflictDto(
        String cellId,
        String filePath,
        DeltaSideDto ours,
        DeltaSideDto theirs) {
}
