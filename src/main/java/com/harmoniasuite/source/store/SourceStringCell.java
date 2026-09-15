package com.harmoniasuite.source.store;

import java.util.Arrays;
import java.util.Objects;

public record SourceStringCell(
        long id,
        long sheetId,
        long rowId,
        int subrowId,
        int columnIndex,
        String macroText,
        byte[] macroHash,
        byte[] rawHash) {

    public SourceStringCell {
        Objects.requireNonNull(macroText, "macroText");
        macroHash = copyHash(macroHash, "macroHash");
        rawHash = rawHash == null ? null : copyHash(rawHash, "rawHash");
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
