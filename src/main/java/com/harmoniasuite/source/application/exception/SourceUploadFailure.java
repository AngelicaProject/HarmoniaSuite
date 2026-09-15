package com.harmoniasuite.source.application.exception;

import com.harmoniasuite.source.domain.SourceUploadErrorCode;

public final class SourceUploadFailure extends RuntimeException {

    private final SourceUploadErrorCode code;

    public SourceUploadFailure(SourceUploadErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public SourceUploadFailure(SourceUploadErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public SourceUploadErrorCode code() {
        return code;
    }
}
