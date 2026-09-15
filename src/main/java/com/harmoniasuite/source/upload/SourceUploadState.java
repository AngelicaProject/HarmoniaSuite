package com.harmoniasuite.source.upload;

public enum SourceUploadState {
    UPLOADING,
    QUEUED,
    VERIFYING,
    MATERIALIZING,
    STORING,
    COMPLETED,
    FAILED;

    public boolean canTransitionTo(SourceUploadState target) {
        return switch (this) {
            case UPLOADING -> target == QUEUED || target == FAILED;
            case QUEUED -> target == VERIFYING || target == FAILED;
            case VERIFYING -> target == MATERIALIZING || target == FAILED;
            case MATERIALIZING -> target == STORING || target == FAILED;
            case STORING -> target == COMPLETED || target == FAILED;
            case COMPLETED, FAILED -> false;
        };
    }

    public boolean processing() {
        return this == QUEUED || this == VERIFYING || this == MATERIALIZING || this == STORING;
    }
}
