package com.harmoniasuite.source.application.model;

import com.harmoniasuite.source.domain.SourceSnapshot;
import com.harmoniasuite.source.domain.SourceUploadCreationStatus;
import java.util.Objects;

public record SourceUploadCreationResult(SourceUploadCreationStatus status,
                                         SourceSnapshot snapshot,
                                         SourceUploadSession upload) {
    public SourceUploadCreationResult {
        Objects.requireNonNull(status, "status");
    }
}
