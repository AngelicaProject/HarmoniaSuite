package com.harmoniasuite.service.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.harmoniasuite.TestDatabases;
import com.harmoniasuite.config.HarmoniaProperties;
import com.harmoniasuite.config.WorkspacePaths;
import com.harmoniasuite.dto.SourceSnapshotUploadRequest;
import com.harmoniasuite.dto.SourceUploadResponse;
import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import com.harmoniasuite.exception.HarmoniaSuiteConflictException;
import com.harmoniasuite.exception.SourceUploadTooLargeException;
import com.harmoniasuite.source.artifact.JdbcSourceArtifactRegistry;
import com.harmoniasuite.source.artifact.SourceArtifactPath;
import com.harmoniasuite.source.store.JdbcSourceSnapshotStore;
import com.harmoniasuite.source.store.SourceSnapshotStore;
import com.harmoniasuite.source.upload.JdbcSourceUploadSessionStore;
import com.harmoniasuite.source.upload.SourceUploadSessionStore;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class SourceUploadServiceTest {

    private static final String SNAPSHOT_ID = "sha256:" + "a".repeat(64);
    private static final String CONTENT_ID = "sha256:" + "b".repeat(64);

    private HarmoniaProperties properties;
    private SourceArtifactPath paths;
    private SourceUploadSessionStore sessions;
    private SourceUploadService service;
    private List<String> queued;

    @BeforeEach
    void setUp(@TempDir Path workspace) {
        properties = new HarmoniaProperties();
        properties.setWorkspace(workspace.toString());
        properties.getSourceIngestion().setMaxUploadBytes(100);
        properties.getSourceIngestion().setMaxHxsBytes(100);
        properties.getSourceIngestion().setMaxChunkBytes(4);
        JdbcTemplate jdbc = TestDatabases.coreSqlite(workspace);
        SourceSnapshotStore snapshots = new JdbcSourceSnapshotStore(jdbc);
        paths = new SourceArtifactPath(new WorkspacePaths(properties), properties);
        sessions = new JdbcSourceUploadSessionStore(jdbc);
        queued = new ArrayList<>();
        service = new SourceUploadService(
                new SourceSnapshotService(snapshots, new JdbcSourceArtifactRegistry(jdbc)),
                snapshots, sessions, paths, properties, queued::add);
    }

    @Test
    void appendsSequentialChunksAndQueuesOnlyAfterExactCompletion() {
        SourceUploadResponse created = service.create(request("identity", 4, 4));

        SourceUploadResponse first = service.append(created.uploadId(), 0L, 2L,
                new ByteArrayInputStream(new byte[]{1, 2}));
        assertEquals(2L, first.nextOffset());
        assertEquals(2L, service.status(created.uploadId()).nextOffset());

        assertThrows(com.harmoniasuite.source.upload.UploadOffsetConflictException.class,
                () -> service.append(created.uploadId(), 0L, 1L,
                        new ByteArrayInputStream(new byte[]{3})));
        SourceUploadResponse second = service.append(created.uploadId(), 2L, 2L,
                new ByteArrayInputStream(new byte[]{3, 4}));
        assertEquals(4L, second.nextOffset());

        SourceUploadResponse completed = service.complete(created.uploadId());
        assertEquals("QUEUED", completed.state());
        assertEquals(List.of(created.uploadId()), queued);
        assertThrows(HarmoniaSuiteConflictException.class,
                () -> service.append(created.uploadId(), 4L, 1L,
                        new ByteArrayInputStream(new byte[]{5})));
    }

    @Test
    void validatesTransportAndChunkLimitsBeforeWriting() {
        assertThrows(HarmoniaSuiteBadRequestException.class,
                () -> service.create(request("gzip", 4, 4)));
        assertThrows(HarmoniaSuiteBadRequestException.class,
                () -> service.create(request("identity", 3, 4)));

        SourceUploadResponse created = service.create(request("identity", 4, 4));
        assertThrows(SourceUploadTooLargeException.class,
                () -> service.append(created.uploadId(), 0L, 5L,
                        new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5})));
        assertEquals(0L, service.status(created.uploadId()).nextOffset());
    }

    @Test
    void statusReconcilesDurableStagingBytesAheadOfDatabaseOffset() throws Exception {
        SourceUploadResponse created = service.create(request("identity", 4, 4));
        Files.write(paths.payloadPath(created.uploadId()), new byte[]{1, 2, 3});

        SourceUploadResponse status = service.status(created.uploadId());

        assertEquals(3L, status.receivedBytes());
        assertEquals(3L, sessions.find(created.uploadId()).orElseThrow().receivedBytes());
    }

    private static SourceSnapshotUploadRequest request(String encoding, long uploadSize,
                                                        long uncompressedSize) {
        return new SourceSnapshotUploadRequest(1, "2026.09", "en", "full", SNAPSHOT_ID,
                CONTENT_ID, "extractor", "lumina", 1L, 2L, 3L, encoding, uploadSize,
                uncompressedSize);
    }
}
