package com.harmoniasuite.source.application.port;

import com.harmoniasuite.source.application.model.StoredSourceArtifact;
import com.harmoniasuite.source.domain.Sha256Digest;
import com.harmoniasuite.source.domain.Sha256Id;
import java.io.IOException;
import java.nio.file.Path;

/** Blob-store boundary for immutable compressed HXS artifacts. */
public interface SourceArtifactStorage {

    StoredSourceArtifact storeHxs(Path hxsPath, Sha256Id trustedSnapshotId,
                                  long uncompressedSize, Sha256Digest hxsFileHash,
                                  int compressionLevel) throws IOException;

    StoredSourceArtifact storeCompressed(Path compressedPath, Sha256Id trustedSnapshotId,
                                         long uncompressedSize, Sha256Digest hxsFileHash)
            throws IOException;
}
