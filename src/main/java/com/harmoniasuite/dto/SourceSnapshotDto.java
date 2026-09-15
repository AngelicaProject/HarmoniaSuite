package com.harmoniasuite.dto;

import com.harmoniasuite.source.store.SourceSnapshot;

/** Public metadata projection for a trusted canonical source snapshot. */
public record SourceSnapshotDto(
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

    public static SourceSnapshotDto from(SourceSnapshot snapshot) {
        return new SourceSnapshotDto(
                snapshot.snapshotId(), snapshot.contentId(), snapshot.hxsVersion(),
                snapshot.gameVersion(), snapshot.language(), snapshot.scope(),
                snapshot.extractorVersion(), snapshot.luminaVersion(), snapshot.sheetCount(),
                snapshot.rowCount(), snapshot.stringCellCount());
    }
}
