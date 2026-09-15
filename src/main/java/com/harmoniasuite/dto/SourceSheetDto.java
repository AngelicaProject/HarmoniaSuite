package com.harmoniasuite.dto;

import com.harmoniasuite.source.store.SourceSheet;

/** Public metadata projection for a sheet in a trusted canonical source snapshot. */
public record SourceSheetDto(
        String name,
        int variant,
        String effectiveLanguage,
        long columnCount,
        long rowCount,
        byte[] schemaHash,
        byte[] technicalHash,
        byte[] stringHash,
        byte[] contentHash) {

    public static SourceSheetDto from(SourceSheet sheet) {
        return new SourceSheetDto(
                sheet.name(), sheet.variant(), sheet.effectiveLanguage(), sheet.columnCount(),
                sheet.rowCount(), sheet.schemaHash(), sheet.technicalHash(), sheet.stringHash(),
                sheet.contentHash());
    }
}
