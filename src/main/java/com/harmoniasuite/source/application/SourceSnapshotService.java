package com.harmoniasuite.source.application;

import com.harmoniasuite.source.application.exception.HarmoniaSuiteBadRequestException;
import com.harmoniasuite.source.application.exception.HarmoniaSuiteConflictException;
import com.harmoniasuite.source.application.exception.HarmoniaSuiteNotFoundException;
import com.harmoniasuite.source.application.model.SourceSnapshotAvailabilityResult;
import com.harmoniasuite.source.application.port.SourceArtifactRepository;
import com.harmoniasuite.source.application.port.SourceSnapshotRepository;
import com.harmoniasuite.source.domain.SourceSnapshot;
import com.harmoniasuite.source.domain.SourceSnapshotAvailability;
import com.harmoniasuite.source.domain.SourceSnapshotMetadata;
import com.harmoniasuite.source.domain.SourceSheet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Application use cases for trusted source snapshot discovery and preflight. */
public final class SourceSnapshotService {

    private final SourceSnapshotRepository snapshots;
    private final SourceArtifactRepository artifacts;

    public SourceSnapshotService(SourceSnapshotRepository snapshots,
                                 SourceArtifactRepository artifacts) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    }

    public SourceSnapshotAvailabilityResult preflight(SourceSnapshotMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        Optional<SourceSnapshot> stored = read(() -> snapshots.findBySnapshotId(metadata.snapshotId().value()));
        if (stored.isEmpty()) {
            return new SourceSnapshotAvailabilityResult(SourceSnapshotAvailability.UPLOAD_REQUIRED,
                    metadata.snapshotId().value(), null);
        }
        SourceSnapshot snapshot = stored.get();
        if (!snapshot.metadata().equals(metadata)) {
            throw new HarmoniaSuiteConflictException(
                    "snapshot metadata conflicts with the trusted source snapshot");
        }
        if (read(() -> artifacts.findBySnapshotDbId(snapshot.id())).isEmpty()) {
            return new SourceSnapshotAvailabilityResult(SourceSnapshotAvailability.UPLOAD_REQUIRED,
                    metadata.snapshotId().value(), null);
        }
        return new SourceSnapshotAvailabilityResult(SourceSnapshotAvailability.AVAILABLE,
                metadata.snapshotId().value(), snapshot);
    }

    public List<SourceSnapshot> listSnapshots() {
        return read(snapshots::listSnapshots);
    }

    public SourceSnapshot getSnapshot(String snapshotId) {
        return findSnapshot(snapshotId);
    }

    public List<SourceSheet> listSheets(String snapshotId) {
        findSnapshot(snapshotId);
        return read(() -> snapshots.findSheets(snapshotId));
    }

    private SourceSnapshot findSnapshot(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new HarmoniaSuiteBadRequestException("snapshotId must not be blank");
        }
        return read(() -> snapshots.findBySnapshotId(snapshotId))
                .orElseThrow(() -> new HarmoniaSuiteNotFoundException(
                        "source snapshot was not found"));
    }

    private static <T> T read(Supplier<T> operation) {
        return operation.get();
    }
}
