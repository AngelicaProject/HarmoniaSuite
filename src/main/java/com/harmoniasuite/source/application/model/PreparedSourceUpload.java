package com.harmoniasuite.source.application.model;

import com.harmoniasuite.source.domain.Sha256Digest;
import java.nio.file.Path;

public record PreparedSourceUpload(Path transportPath, Path hxsPath, long hxsSize,
                                   Sha256Digest transportHash, Sha256Digest hxsHash) {
}
