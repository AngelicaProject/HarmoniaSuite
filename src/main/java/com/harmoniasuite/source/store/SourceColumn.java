package com.harmoniasuite.source.store;

import java.util.Objects;

public record SourceColumn(long sheetId, int columnIndex, long offset, String type) {

    public SourceColumn {
        Objects.requireNonNull(type, "type");
    }
}
