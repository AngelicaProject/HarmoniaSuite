package com.harmoniasuite.source.hxs;

/** Fail-closed error raised while reading an already Atlas-verified HXS artifact. */
public class HxsReadException extends RuntimeException {

    public enum Reason {
        INPUT,
        OPEN_FAILURE,
        SCHEMA,
        METADATA_MISMATCH,
        READ_FAILURE
    }

    private final Reason reason;

    public HxsReadException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public HxsReadException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
