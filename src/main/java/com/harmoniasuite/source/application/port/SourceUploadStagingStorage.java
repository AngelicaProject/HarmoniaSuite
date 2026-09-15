package com.harmoniasuite.source.application.port;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;
import com.harmoniasuite.source.application.model.PreparedSourceUpload;
import com.harmoniasuite.source.domain.TransportEncoding;

/** Filesystem boundary for resumable upload chunks and managed ingestion payloads. */
public interface SourceUploadStagingStorage {

    void create(UUID uploadId) throws IOException;

    long receivedBytes(UUID uploadId) throws IOException;

    boolean exists(UUID uploadId);

    long append(UUID uploadId, InputStream body, long contentLength, long maxChunkBytes,
                long declaredUploadSize) throws IOException;

    void delete(UUID uploadId) throws IOException;

    Path directory(UUID uploadId);

    Path payload(UUID uploadId);

    Path snapshot(UUID uploadId);

    Path snapshotTemp(UUID uploadId);

    void deleteFileIfPresent(Path path);

    PreparedSourceUpload prepareForIngestion(UUID uploadId, TransportEncoding encoding,
                                             long uploadSize, long uncompressedSize,
                                             long maxUploadBytes, long maxHxsBytes);
}
