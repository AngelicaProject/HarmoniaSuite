package com.harmoniasuite.source.domain;

import java.util.Objects;

public record SourceStringCell(
        long id,
        long sheetId,
        long rowId,
        int subrowId,
        int columnIndex,
        String macroText,
        Sha256Digest macroHash,
        Sha256Digest rawHash) {

    public SourceStringCell {
        Objects.requireNonNull(macroText, "macroText");
        Objects.requireNonNull(macroHash, "macroHash");
    }
}
