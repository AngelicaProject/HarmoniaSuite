package com.harmoniasuite.source.atlas;

/** Verified metadata returned by Atlas after a successful HXS inspection. */
public record AtlasInspection(
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
}
