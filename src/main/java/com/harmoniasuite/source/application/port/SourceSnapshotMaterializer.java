package com.harmoniasuite.source.application.port;

import com.harmoniasuite.source.domain.SourceSnapshot;
import com.harmoniasuite.source.domain.SourceSnapshotMetadata;
import java.nio.file.Path;

/** Application boundary for streaming an Atlas-verified HXS into the canonical database. */
public interface SourceSnapshotMaterializer {

    SourceSnapshot materialize(Path managedHxsPath, SourceSnapshotMetadata trustedMetadata);
}
