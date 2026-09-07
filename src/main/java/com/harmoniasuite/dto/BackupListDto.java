package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BackupListDto(List<BackupDto> backups, int retention, int autoIntervalMinutes, long usedBytes, long estimatedBytes) {
}
