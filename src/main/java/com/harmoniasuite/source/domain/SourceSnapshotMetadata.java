package com.harmoniasuite.source.domain;

import java.util.Objects;

/** Immutable metadata identity shared by preflight, verification, and persistence. */
public record SourceSnapshotMetadata(
        int hxsVersion,
        String gameVersion,
        String language,
        String scope,
        Sha256Id snapshotId,
        Sha256Id contentId,
        String extractorVersion,
        String luminaVersion,
        long sheetCount,
        long rowCount,
        long stringCellCount) {

    public SourceSnapshotMetadata {
        requireText(gameVersion, "gameVersion");
        requireText(language, "language");
        requireText(scope, "scope");
        requireText(extractorVersion, "extractorVersion");
        requireText(luminaVersion, "luminaVersion");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(contentId, "contentId");
        SourceScope.parse(scope);
        if (hxsVersion != 1) {
            throw new IllegalArgumentException("hxsVersion must be 1");
        }
        if (sheetCount < 0 || rowCount < 0 || stringCellCount < 0) {
            throw new IllegalArgumentException("source snapshot counts must be non-negative");
        }
    }

    public SourceSnapshotMetadata(int hxsVersion, String gameVersion, String language,
                                  String scope, String snapshotId, String contentId,
                                  String extractorVersion, String luminaVersion,
                                  long sheetCount, long rowCount, long stringCellCount) {
        this(hxsVersion, gameVersion, language, scope, Sha256Id.parse(snapshotId),
                Sha256Id.parse(contentId), extractorVersion, luminaVersion, sheetCount,
                rowCount, stringCellCount);
    }

    public static SourceSnapshotMetadata of(int hxsVersion, String gameVersion, String language,
                                            String scope, String snapshotId, String contentId,
                                            String extractorVersion, String luminaVersion,
                                            long sheetCount, long rowCount, long stringCellCount) {
        return new SourceSnapshotMetadata(hxsVersion, gameVersion, language, scope, snapshotId,
                contentId, extractorVersion, luminaVersion, sheetCount, rowCount,
                stringCellCount);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

}
