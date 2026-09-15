package com.harmoniasuite.source.upload;

import com.harmoniasuite.dto.SourceSnapshotPreflightRequest;

/** Client-supplied metadata retained only as a claim until Atlas verifies the HXS. */
public record SourceUploadClaim(
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

    public SourceSnapshotPreflightRequest asPreflightRequest() {
        return new SourceSnapshotPreflightRequest(hxsVersion, gameVersion, language, scope,
                snapshotId, contentId, extractorVersion, luminaVersion, sheetCount, rowCount,
                stringCellCount);
    }

    public static SourceUploadClaim from(SourceSnapshotPreflightRequest request) {
        return new SourceUploadClaim(request.hxsVersion(), request.gameVersion(), request.language(),
                request.scope(), request.snapshotId(), request.contentId(), request.extractorVersion(),
                request.luminaVersion(), request.sheetCount(), request.rowCount(),
                request.stringCellCount());
    }
}
