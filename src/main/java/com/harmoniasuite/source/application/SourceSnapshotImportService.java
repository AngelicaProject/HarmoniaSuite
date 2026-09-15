package com.harmoniasuite.source.application;

import com.harmoniasuite.source.application.port.AtlasInspector;
import com.harmoniasuite.source.application.port.SourceSnapshotMaterializer;
import com.harmoniasuite.source.domain.SourceSnapshot;
import com.harmoniasuite.source.domain.SourceSnapshotMetadata;
import java.nio.file.Path;
import java.util.Objects;

/** Coordinates the trusted source cutover: Atlas verifies once, then the same handle is materialized. */
public final class SourceSnapshotImportService {

    private final AtlasInspector atlas;
    private final SourceSnapshotMaterializer materializer;

    public SourceSnapshotImportService(AtlasInspector atlas,
                                       SourceSnapshotMaterializer materializer) {
        this.atlas = Objects.requireNonNull(atlas, "atlas");
        this.materializer = Objects.requireNonNull(materializer, "materializer");
    }

    public VerifiedHxs verify(Path managedHxsPath) {
        Objects.requireNonNull(managedHxsPath, "managedHxsPath");
        Path normalized = managedHxsPath.toAbsolutePath().normalize();
        return new VerifiedHxs(normalized, atlas.inspect(normalized));
    }

    public SourceSnapshot importVerified(VerifiedHxs verified) {
        Objects.requireNonNull(verified, "verified");
        return materializer.materialize(verified.path(), verified.metadata());
    }

    public SourceSnapshot importSnapshot(Path managedHxsPath) {
        return importVerified(verify(managedHxsPath));
    }

    public record VerifiedHxs(Path path, SourceSnapshotMetadata metadata) {
        public VerifiedHxs {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(metadata, "metadata");
        }
    }
}
