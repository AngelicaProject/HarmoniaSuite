package com.harmoniasuite.source.domain;

public record SourceRow(long sheetId, long rowId, int subrowId,
                        Sha256Digest rowHash, Sha256Digest technicalHash, Sha256Digest stringHash) {

    public SourceRow {
        java.util.Objects.requireNonNull(rowHash, "rowHash");
        java.util.Objects.requireNonNull(technicalHash, "technicalHash");
        java.util.Objects.requireNonNull(stringHash, "stringHash");
    }
}
