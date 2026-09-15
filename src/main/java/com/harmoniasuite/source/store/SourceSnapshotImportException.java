package com.harmoniasuite.source.store;

/** Import consistency failure; the surrounding transaction must be rolled back. */
public class SourceSnapshotImportException extends RuntimeException {

    public SourceSnapshotImportException(String message) {
        super(message);
    }

    public SourceSnapshotImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
