package com.harmoniasuite.source.atlas;

/** Fail-closed error raised at the Atlas trust boundary. */
public class AtlasException extends RuntimeException {

    public enum Reason {
        CONFIGURATION,
        INPUT_FILE,
        LAUNCH_FAILURE,
        PROCESS_IO,
        TIMEOUT,
        NON_ZERO_EXIT,
        INVALID_OUTPUT,
        OUTPUT_LIMIT,
        INTERRUPTED
    }

    private final Reason reason;

    public AtlasException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public AtlasException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
