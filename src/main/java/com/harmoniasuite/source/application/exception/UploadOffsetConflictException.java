package com.harmoniasuite.source.application.exception;

public final class UploadOffsetConflictException extends RuntimeException {

    private final long expectedOffset;

    public UploadOffsetConflictException(long expectedOffset) {
        super("upload offset does not match the server offset");
        this.expectedOffset = expectedOffset;
    }

    public long expectedOffset() {
        return expectedOffset;
    }
}
