package com.harmoniasuite.source.hxs;

/** Callback sink used by the reader so callers never need a whole-snapshot collection. */
public interface HxsSourceSink {

    /** Return false to stop after metadata, for example when a snapshot is already stored. */
    default boolean begin(HxsMetadata metadata) {
        return true;
    }

    default void beginSheet(HxsSheet sheet) {
    }

    default void column(HxsColumn column) {
    }

    default void row(HxsRow row) {
    }

    default void stringCell(HxsStringCell cell) {
    }

    default void endSheet() {
    }

    default void end() {
    }
}
