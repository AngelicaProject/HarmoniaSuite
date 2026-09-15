package com.harmoniasuite.source.hxs;

import java.util.Arrays;
import java.util.Objects;

public record HxsStringCell(
        long rowId,
        int subrowId,
        int columnIndex,
        String macroText,
        byte[] macroHash,
        byte[] rawHash) {

    public HxsStringCell {
        if (rowId < 0 || rowId > 4_294_967_295L) {
            throw new IllegalArgumentException("rowId is outside the HXS range");
        }
        if (subrowId < 0 || subrowId > 65_535) {
            throw new IllegalArgumentException("subrowId is outside the HXS range");
        }
        if (columnIndex < 0) {
            throw new IllegalArgumentException("columnIndex must be non-negative");
        }
        Objects.requireNonNull(macroText, "macroText");
        macroHash = copyHash(macroHash, "macroHash");
        if (rawHash != null && rawHash.length != 32) {
            throw new IllegalArgumentException("rawHash must contain exactly 32 bytes");
        }
        rawHash = rawHash == null ? null : Arrays.copyOf(rawHash, rawHash.length);
    }

    @Override
    public byte[] macroHash() {
        return macroHash.clone();
    }

    @Override
    public byte[] rawHash() {
        return rawHash == null ? null : rawHash.clone();
    }

    private static byte[] copyHash(byte[] value, String name) {
        Objects.requireNonNull(value, name);
        if (value.length != 32) {
            throw new IllegalArgumentException(name + " must contain exactly 32 bytes");
        }
        return Arrays.copyOf(value, value.length);
    }
}
