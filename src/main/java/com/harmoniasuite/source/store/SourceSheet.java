package com.harmoniasuite.source.store;

import java.util.Arrays;
import java.util.Objects;

public record SourceSheet(
        long id,
        long snapshotDbId,
        String name,
        int variant,
        String effectiveLanguage,
        long columnCount,
        long rowCount,
        byte[] schemaHash,
        byte[] technicalHash,
        byte[] stringHash,
        byte[] contentHash) {

    public SourceSheet {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(effectiveLanguage, "effectiveLanguage");
        schemaHash = copyHash(schemaHash, "schemaHash");
        technicalHash = copyHash(technicalHash, "technicalHash");
        stringHash = copyHash(stringHash, "stringHash");
        contentHash = copyHash(contentHash, "contentHash");
    }

    @Override
    public byte[] schemaHash() {
        return schemaHash.clone();
    }

    @Override
    public byte[] technicalHash() {
        return technicalHash.clone();
    }

    @Override
    public byte[] stringHash() {
        return stringHash.clone();
    }

    @Override
    public byte[] contentHash() {
        return contentHash.clone();
    }

    private static byte[] copyHash(byte[] value, String name) {
        Objects.requireNonNull(value, name);
        if (value.length != 32) {
            throw new IllegalArgumentException(name + " must contain exactly 32 bytes");
        }
        return Arrays.copyOf(value, value.length);
    }
}
