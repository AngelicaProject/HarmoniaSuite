package com.harmoniasuite.source.store;

public record SourceColumn(long sheetId, int columnIndex, long offset, int type) {

    public SourceColumn {
        if (columnIndex < 0) {
            throw new IllegalArgumentException("columnIndex must be non-negative");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative");
        }
    }
}
