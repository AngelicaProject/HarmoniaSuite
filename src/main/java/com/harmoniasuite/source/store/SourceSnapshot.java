package com.harmoniasuite.source.store;

import java.util.Objects;

/** Immutable source snapshot metadata; the numeric id is a database identity only. */
public record SourceSnapshot(
        long id,
        String snapshotId,
        String contentId,
        int hxsVersion,
        String gameVersion,
        String language,
        String scope,
        String extractorVersion,
        String luminaVersion,
        long sheetCount,
        long rowCount,
        long stringCellCount) {

    public SourceSnapshot {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(contentId, "contentId");
        Objects.requireNonNull(gameVersion, "gameVersion");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(extractorVersion, "extractorVersion");
        Objects.requireNonNull(luminaVersion, "luminaVersion");
    }
}
