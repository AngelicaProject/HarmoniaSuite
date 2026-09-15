package com.harmoniasuite.source.application.port;

import com.harmoniasuite.source.domain.SourceSnapshotMetadata;
import java.nio.file.Path;

/** Application boundary for one trusted inspection of a managed HXS artifact. */
public interface AtlasInspector {

    SourceSnapshotMetadata inspect(Path managedHxsPath);
}
