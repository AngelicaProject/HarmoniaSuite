package com.harmoniasuite.source.hxs;

import java.util.Objects;

public record HxsColumn(int columnIndex, long offset, String type) {

    public HxsColumn {
        if (columnIndex < 0) {
            throw new IllegalArgumentException("columnIndex must be non-negative");
        }
        Objects.requireNonNull(type, "type");
    }
}
