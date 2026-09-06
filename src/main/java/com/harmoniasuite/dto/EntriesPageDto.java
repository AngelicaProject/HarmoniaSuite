package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EntriesPageDto(
        List<EntryDto> entries,
        long total,
        int offset,
        int limit) {
}
