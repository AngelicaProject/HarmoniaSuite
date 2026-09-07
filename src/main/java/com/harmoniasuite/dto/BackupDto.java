package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BackupDto(String name, long size, String createdAt) {
}
