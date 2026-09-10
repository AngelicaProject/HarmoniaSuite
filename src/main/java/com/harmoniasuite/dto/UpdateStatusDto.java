package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpdateStatusDto(
        String version,
        boolean supported,
        String mode,
        Boolean needsToolchain,
        String currentSha,
        String latestSha,
        Integer behindBy,
        List<String> subjects,
        Boolean updateAvailable,
        UpdateState state,
        String reason) {
}
