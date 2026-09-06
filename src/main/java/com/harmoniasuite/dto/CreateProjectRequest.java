package com.harmoniasuite.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateProjectRequest(@NotBlank String id, String root) {
}
