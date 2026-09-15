package com.harmoniasuite.source.application.model;

import com.harmoniasuite.source.domain.SourceSnapshot;
import com.harmoniasuite.source.domain.SourceSnapshotAvailability;
import java.util.Objects;

public record SourceSnapshotAvailabilityResult(SourceSnapshotAvailability status,
                                               String snapshotId,
                                               SourceSnapshot snapshot) {
    public SourceSnapshotAvailabilityResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(snapshotId, "snapshotId");
    }
}
