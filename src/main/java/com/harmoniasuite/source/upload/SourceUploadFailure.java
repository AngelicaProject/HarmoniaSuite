package com.harmoniasuite.source.upload;

public final class SourceUploadFailure extends RuntimeException {

    private final String code;

    public SourceUploadFailure(String code, String message) {
        super(message);
        this.code = code;
    }

    public SourceUploadFailure(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
