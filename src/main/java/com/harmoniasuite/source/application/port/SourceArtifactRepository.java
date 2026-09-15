package com.harmoniasuite.source.application.port;

import com.harmoniasuite.source.domain.SourceArtifact;
import java.util.Optional;

/** SQL repository for the one immutable artifact belonging to a canonical snapshot. */
public interface SourceArtifactRepository {

    Optional<SourceArtifact> findBySnapshotId(String snapshotId);

    Optional<SourceArtifact> findBySnapshotDbId(long snapshotDbId);

    SourceArtifact register(SourceArtifact artifact);
}
