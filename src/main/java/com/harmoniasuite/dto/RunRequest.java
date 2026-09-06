package com.harmoniasuite.dto;

import java.util.List;
import java.util.UUID;

public record RunRequest(
        String action,
        UUID projectId,
        String root,
        String output,
        List<String> files,
        Boolean force,
        String model,
        String reasoning
) {
}
