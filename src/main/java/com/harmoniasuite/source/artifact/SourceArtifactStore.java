package com.harmoniasuite.source.artifact;

import java.io.IOException;
import java.nio.file.Path;

/** Blob-store boundary for immutable compressed HXS artifacts. */
public interface SourceArtifactStore {

    StoredSourceArtifact storeHxs(Path hxsPath, String trustedSnapshotId,
                                  long uncompressedSize, byte[] hxsFileHash,
                                  int compressionLevel) throws IOException;

    StoredSourceArtifact storeCompressed(Path compressedPath, String trustedSnapshotId,
                                         long uncompressedSize, byte[] hxsFileHash)
            throws IOException;
}
