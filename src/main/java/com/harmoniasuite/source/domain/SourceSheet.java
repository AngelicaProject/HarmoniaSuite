package com.harmoniasuite.source.domain;

import java.util.Objects;

public record SourceSheet(
        long id,
        long snapshotDbId,
        String name,
        int variant,
        String effectiveLanguage,
        long columnCount,
        long rowCount,
        Sha256Digest schemaHash,
        Sha256Digest technicalHash,
        Sha256Digest stringHash,
        Sha256Digest contentHash) {

    public SourceSheet {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(effectiveLanguage, "effectiveLanguage");
        Objects.requireNonNull(schemaHash, "schemaHash");
        Objects.requireNonNull(technicalHash, "technicalHash");
        Objects.requireNonNull(stringHash, "stringHash");
        Objects.requireNonNull(contentHash, "contentHash");
    }
}
