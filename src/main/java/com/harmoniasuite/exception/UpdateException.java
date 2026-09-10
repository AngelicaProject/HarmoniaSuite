package com.harmoniasuite.exception;

import com.harmoniasuite.util.ScrubSupport;

public final class UpdateException extends RuntimeException {

    private final UpdateErrorCode errorCode;

    public UpdateException(UpdateErrorCode errorCode) {
        this(errorCode, null, null);
    }

    public UpdateException(UpdateErrorCode errorCode, Throwable cause) {
        this(errorCode, null, cause);
    }

    public UpdateException(UpdateErrorCode errorCode, String detail) {
        this(errorCode, detail, null);
    }

    public UpdateException(UpdateErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode.userMessage(), cause);
        this.errorCode = errorCode;
        this.detail = detail;
    }

    private final String detail;

    public String code() {
        return errorCode.code();
    }

    public UpdateErrorCode errorCode() {
        return errorCode;
    }

    public String diagnosticMessage() {
        String diagnostic = detail;
        if (diagnostic == null || diagnostic.isBlank()) {
            Throwable cause = getCause();
            diagnostic = cause == null ? null : cause.getMessage();
        }
        return diagnostic == null || diagnostic.isBlank()
                ? getMessage()
                : getMessage() + ": " + ScrubSupport.scrub(diagnostic);
    }
}
