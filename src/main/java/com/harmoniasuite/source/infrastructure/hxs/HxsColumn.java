package com.harmoniasuite.source.infrastructure.hxs;

public record HxsColumn(int columnIndex, long offset, int type) {

    public HxsColumn {
        if (columnIndex < 0) {
            throw new IllegalArgumentException("columnIndex must be non-negative");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative");
        }
    }
}
