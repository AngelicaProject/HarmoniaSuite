package com.harmoniasuite.source.artifact;

import java.util.Optional;

/** SQL registry for the one immutable artifact belonging to a canonical snapshot. */
public interface SourceArtifactRegistry {

    Optional<SourceArtifact> findBySnapshotId(String snapshotId);

    Optional<SourceArtifact> findBySnapshotDbId(long snapshotDbId);

    SourceArtifact register(SourceArtifact artifact);
}
