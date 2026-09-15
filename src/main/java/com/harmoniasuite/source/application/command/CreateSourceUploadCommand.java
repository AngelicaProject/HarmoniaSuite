package com.harmoniasuite.source.application.command;

import com.harmoniasuite.source.domain.SourceSnapshotMetadata;
import java.util.Objects;

public record CreateSourceUploadCommand(SourceSnapshotMetadata metadata,
                                        String transportEncoding,
                                        long uploadSize,
                                        long uncompressedSize) {
    public CreateSourceUploadCommand {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(transportEncoding, "transportEncoding");
    }
}
