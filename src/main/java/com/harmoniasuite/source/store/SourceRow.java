package com.harmoniasuite.source.store;

import java.util.Arrays;
import java.util.Objects;

public record SourceRow(long sheetId, long rowId, int subrowId,
                        byte[] rowHash, byte[] technicalHash, byte[] stringHash) {

    public SourceRow {
        rowHash = copyHash(rowHash, "rowHash");
        technicalHash = copyHash(technicalHash, "technicalHash");
        stringHash = copyHash(stringHash, "stringHash");
    }

    @Override
    public byte[] rowHash() {
        return rowHash.clone();
    }

    @Override
    public byte[] technicalHash() {
        return technicalHash.clone();
    }

    @Override
    public byte[] stringHash() {
        return stringHash.clone();
    }

    private static byte[] copyHash(byte[] value, String name) {
        Objects.requireNonNull(value, name);
        if (value.length != 32) {
            throw new IllegalArgumentException(name + " must contain exactly 32 bytes");
        }
        return Arrays.copyOf(value, value.length);
    }
}
