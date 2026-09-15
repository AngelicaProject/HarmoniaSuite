package com.harmoniasuite.source.hxs;

import com.harmoniasuite.source.atlas.AtlasInspection;

import java.util.Objects;

/** Metadata read from the HXS artifact before any source records are consumed. */
public record HxsMetadata(
        int hxsVersion,
        String gameVersion,
        String language,
        String scope,
        String snapshotId,
        String contentId,
        String extractorVersion,
        String luminaVersion,
        long sheetCount,
        long rowCount,
        long stringCellCount) {

    public HxsMetadata {
        Objects.requireNonNull(gameVersion, "gameVersion");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(contentId, "contentId");
        Objects.requireNonNull(extractorVersion, "extractorVersion");
        Objects.requireNonNull(luminaVersion, "luminaVersion");
    }

    public static HxsMetadata from(AtlasInspection inspection) {
        Objects.requireNonNull(inspection, "inspection");
        return new HxsMetadata(inspection.hxsVersion(), inspection.gameVersion(), inspection.language(),
                inspection.scope(), inspection.snapshotId(), inspection.contentId(),
                inspection.extractorVersion(), inspection.luminaVersion(), inspection.sheetCount(),
                inspection.rowCount(), inspection.stringCellCount());
    }

    public void requireExactMatch(AtlasInspection inspection) {
        if (!equals(from(inspection))) {
            throw new HxsReadException(HxsReadException.Reason.METADATA_MISMATCH,
                    "HXS metadata does not match the trusted Atlas inspection");
        }
    }
}
