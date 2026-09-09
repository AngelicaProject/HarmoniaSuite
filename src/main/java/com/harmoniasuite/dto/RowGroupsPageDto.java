package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RowGroupsPageDto(
        List<RowGroupDto> groups,
        long totalGroups,
        int offset,
        int limit) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RowGroupDto(
            int row,
            String rowKey,
            List<EntryDto> cells,
            int un) {
    }
}
