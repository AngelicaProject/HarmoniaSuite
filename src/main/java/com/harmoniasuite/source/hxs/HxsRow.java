package com.harmoniasuite.source.hxs;

import java.util.Arrays;
import java.util.Objects;

public record HxsRow(long rowId, int subrowId, byte[] rowHash, byte[] technicalHash, byte[] stringHash) {

    public HxsRow {
        if (rowId < 0 || rowId > 4_294_967_295L) {
            throw new IllegalArgumentException("rowId is outside the HXS range");
        }
        if (subrowId < 0 || subrowId > 65_535) {
            throw new IllegalArgumentException("subrowId is outside the HXS range");
        }
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
