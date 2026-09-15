package com.harmoniasuite.service.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.luben.zstd.ZstdOutputStream;
import com.harmoniasuite.TestDatabases;
import com.harmoniasuite.config.HarmoniaProperties;
import com.harmoniasuite.config.WorkspacePaths;
import com.harmoniasuite.dto.SourceSnapshotUploadRequest;
import com.harmoniasuite.dto.SourceUploadResponse;
import com.harmoniasuite.source.artifact.FilesystemSourceArtifactStore;
import com.harmoniasuite.source.artifact.JdbcSourceArtifactRegistry;
import com.harmoniasuite.source.artifact.SourceArtifactPath;
import com.harmoniasuite.source.artifact.SourceArtifactRegistry;
import com.harmoniasuite.source.artifact.SourceArtifactStore;
import com.harmoniasuite.source.artifact.SourceCompression;
import com.harmoniasuite.source.atlas.AtlasClient;
import com.harmoniasuite.source.atlas.AtlasInspection;
import com.harmoniasuite.source.store.JdbcSourceSnapshotStore;
import com.harmoniasuite.source.store.SourceSnapshotImporter;
import com.harmoniasuite.source.store.SourceSnapshotStore;
import com.harmoniasuite.source.upload.JdbcSourceUploadSessionStore;
import com.harmoniasuite.source.upload.SourceUploadSessionStore;
import com.harmoniasuite.source.upload.SourceUploadSession;
import com.harmoniasuite.source.upload.SourceUploadState;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class SourceIngestionWorkerTest {

    private static final String SNAPSHOT_ID = "sha256:" + "d".repeat(64);
    private static final String CONTENT_ID = "sha256:" + "e".repeat(64);

    @Test
    void verifiesOnceMaterializesAndRegistersOneImmutableArtifact(@TempDir Path workspace)
            throws Exception {
        HarmoniaProperties properties = properties(workspace);
        JdbcTemplate core = TestDatabases.coreSqlite(workspace);
        SourceSnapshotStore snapshots = new JdbcSourceSnapshotStore(core);
        SourceArtifactRegistry artifacts = new JdbcSourceArtifactRegistry(core);
        SourceUploadSessionStore sessions = new JdbcSourceUploadSessionStore(core);
        SourceArtifactPath paths = new SourceArtifactPath(new WorkspacePaths(properties), properties);
        SourceArtifactStore artifactStore = new FilesystemSourceArtifactStore(paths);
        Path hxs = createHxs(workspace.resolve("input.hxs"));
        byte[] payload = Files.readAllBytes(hxs);
        AtlasInspection inspection = new AtlasInspection(1, "2026.09", "en", "full", SNAPSHOT_ID,
                CONTENT_ID, "extractor", "lumina", 1, 0, 0);
        AtomicInteger atlasCalls = new AtomicInteger();
        AtlasClient atlas = atlas(inspection, atlasCalls);
        SourceSnapshotImporter importer = new SourceSnapshotImporter(atlas,
                new com.harmoniasuite.source.hxs.HxsSourceReader(),
                (com.harmoniasuite.source.store.JdbcSourceSnapshotStore) snapshots);
        Executor direct = Runnable::run;
        SourceIngestionWorker worker = new SourceIngestionWorker(sessions, paths, importer, snapshots,
                artifacts, artifactStore, properties, direct);
        SourceUploadService service = new SourceUploadService(
                new SourceSnapshotService(snapshots, artifacts), snapshots, sessions, paths,
                properties, worker);

        SourceUploadResponse created = service.create(new SourceSnapshotUploadRequest(
                1, "2026.09", "en", "full", SNAPSHOT_ID, CONTENT_ID, "extractor", "lumina",
                1L, 0L, 0L, "identity", (long) payload.length, (long) payload.length));
        service.append(created.uploadId(), 0L, (long) payload.length,
                new ByteArrayInputStream(payload));
        service.complete(created.uploadId());
        SourceUploadResponse completed = service.status(created.uploadId());

        assertEquals("COMPLETED", completed.state());
        assertEquals(1, atlasCalls.get());
        assertTrue(snapshots.findBySnapshotId(SNAPSHOT_ID).isPresent());
        assertTrue(artifacts.findBySnapshotId(SNAPSHOT_ID).isPresent());
        assertTrue(Files.isRegularFile(paths.artifactPath(SNAPSHOT_ID)));
        assertFalse(Files.exists(paths.uploadDirectory(created.uploadId())));
    }

    @Test
    void decodesZstdTransportAndStoresTheValidatedCompressedBytes(@TempDir Path workspace)
            throws Exception {
        HarmoniaProperties properties = properties(workspace);
        JdbcTemplate core = TestDatabases.coreSqlite(workspace);
        SourceSnapshotStore snapshots = new JdbcSourceSnapshotStore(core);
        SourceArtifactRegistry artifacts = new JdbcSourceArtifactRegistry(core);
        SourceUploadSessionStore sessions = new JdbcSourceUploadSessionStore(core);
        SourceArtifactPath paths = new SourceArtifactPath(new WorkspacePaths(properties), properties);
        SourceArtifactStore artifactStore = new FilesystemSourceArtifactStore(paths);
        byte[] raw = Files.readAllBytes(createHxs(workspace.resolve("input.hxs")));
        Path compressed = workspace.resolve("input.zst");
        try (OutputStream stream = Files.newOutputStream(compressed);
             ZstdOutputStream zstd = new ZstdOutputStream(stream)) {
            zstd.write(raw);
        }
        AtlasInspection inspection = new AtlasInspection(1, "2026.09", "en", "full", SNAPSHOT_ID,
                CONTENT_ID, "extractor", "lumina", 1, 0, 0);
        AtomicInteger atlasCalls = new AtomicInteger();
        SourceSnapshotImporter importer = new SourceSnapshotImporter(atlas(inspection, atlasCalls),
                new com.harmoniasuite.source.hxs.HxsSourceReader(),
                (com.harmoniasuite.source.store.JdbcSourceSnapshotStore) snapshots);
        SourceIngestionWorker worker = new SourceIngestionWorker(sessions, paths, importer, snapshots,
                artifacts, artifactStore, properties, Runnable::run);
        SourceUploadService service = new SourceUploadService(
                new SourceSnapshotService(snapshots, artifacts), snapshots, sessions, paths,
                properties, worker);

        SourceUploadResponse created = service.create(new SourceSnapshotUploadRequest(
                1, "2026.09", "en", "full", SNAPSHOT_ID, CONTENT_ID, "extractor", "lumina",
                1L, 0L, 0L, "zstd", Files.size(compressed), (long) raw.length));
        byte[] encoded = Files.readAllBytes(compressed);
        service.append(created.uploadId(), 0L, (long) encoded.length,
                new ByteArrayInputStream(encoded));
        service.complete(created.uploadId());
        SourceUploadResponse completed = service.status(created.uploadId());

        assertEquals("COMPLETED", completed.state());
        assertEquals(1, atlasCalls.get());
        SourceCompression.FileDigest restored = SourceCompression.digestDecompressed(
                paths.artifactPath(SNAPSHOT_ID), raw.length, raw.length);
        assertEquals(raw.length, restored.size());
    }

    @Test
    void runtimeMaintenanceDoesNotResetLiveProcessingState(@TempDir Path workspace) throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        Harness harness = harness(workspace, executor);
        SourceUploadResponse created = prepareUpload(harness, workspace);
        assertTrue(harness.sessions.transition(created.uploadId(), SourceUploadState.QUEUED,
                SourceUploadState.VERIFYING));
        executor.clear();

        harness.worker.scheduledMaintenance();

        assertEquals(SourceUploadState.VERIFYING,
                harness.sessions.find(created.uploadId()).orElseThrow().state());
        assertEquals(0, executor.size());
    }

    @Test
    void startupRecoveryRequeuesPersistedProcessingAndCompletes(@TempDir Path workspace)
            throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        Harness harness = harness(workspace, executor);
        SourceUploadResponse created = prepareUpload(harness, workspace);
        assertTrue(harness.sessions.transition(created.uploadId(), SourceUploadState.QUEUED,
                SourceUploadState.VERIFYING));
        executor.clear();

        harness.worker.recoverOnStartup();

        assertEquals(SourceUploadState.QUEUED,
                harness.sessions.find(created.uploadId()).orElseThrow().state());
        assertEquals(1, executor.size());
        executor.runNext();
        assertEquals(SourceUploadState.COMPLETED,
                harness.sessions.find(created.uploadId()).orElseThrow().state());
        assertEquals(1, harness.atlasCalls.get());
    }

    @Test
    void startupRecoveryMarksMissingStagingFailed(@TempDir Path workspace) throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        Harness harness = harness(workspace, executor);
        SourceUploadResponse created = prepareUpload(harness, workspace);
        assertTrue(harness.sessions.transition(created.uploadId(), SourceUploadState.QUEUED,
                SourceUploadState.VERIFYING));
        Files.delete(harness.paths.payloadPath(created.uploadId()));
        Files.delete(harness.paths.uploadDirectory(created.uploadId()));
        executor.clear();

        harness.worker.recoverOnStartup();

        SourceUploadSession failed = harness.sessions.find(created.uploadId()).orElseThrow();
        assertEquals(SourceUploadState.FAILED, failed.state());
        assertEquals("STAGING_MISSING", failed.errorCode());
        assertEquals(0, executor.size());
    }

    @Test
    void rejectedExecutorLeavesCompletedUploadQueuedForLaterRetry(@TempDir Path workspace)
            throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.reject = true;
        Harness harness = harness(workspace, executor);
        SourceUploadResponse created = prepareUpload(harness, workspace);

        SourceUploadSession queued = harness.sessions.find(created.uploadId()).orElseThrow();
        assertEquals(SourceUploadState.QUEUED, queued.state());
        assertNull(queued.errorCode());
        assertTrue(Files.isDirectory(harness.paths.uploadDirectory(created.uploadId())));

        executor.reject = false;
        harness.worker.scheduledMaintenance();
        executor.runNext();

        assertEquals(SourceUploadState.COMPLETED,
                harness.sessions.find(created.uploadId()).orElseThrow().state());
    }

    @Test
    void cleanupReReadsCandidateAndKeepsFreshSession(@TempDir Path workspace) throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        Harness harness = harness(workspace, executor);
        SourceUploadResponse created = harness.service.create(request("identity", 1, 1));
        harness.jdbc.update("UPDATE source_upload_sessions SET updated_at_ms = 0 WHERE upload_id = ?",
                created.uploadId());
        RefreshingSessionStore refreshing = new RefreshingSessionStore(harness.sessions);
        SourceIngestionWorker cleanupWorker = worker(harness, refreshing, executor);

        cleanupWorker.cleanupExpired();

        assertTrue(harness.sessions.find(created.uploadId()).isPresent());
        assertTrue(Files.isDirectory(harness.paths.uploadDirectory(created.uploadId())));
    }

    @Test
    void cleanupAndAppendShareUploadLock(@TempDir Path workspace) throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        Harness harness = harness(workspace, executor);
        SourceUploadResponse created = harness.service.create(request("identity", 1, 1));
        harness.jdbc.update("UPDATE source_upload_sessions SET updated_at_ms = 0 WHERE upload_id = ?",
                created.uploadId());
        BlockingCleanupStore cleanupStore = new BlockingCleanupStore(harness.sessions);
        SourceIngestionWorker cleanupWorker = worker(harness, cleanupStore, executor);
        BlockingInputStream input = new BlockingInputStream();
        AtomicReference<Throwable> appendFailure = new AtomicReference<>();
        Thread appender = new Thread(() -> {
            try {
                harness.service.append(created.uploadId(), 0L, 1L, input);
            } catch (Throwable failure) {
                appendFailure.set(failure);
            }
        });
        appender.start();
        assertTrue(input.entered.await(5, TimeUnit.SECONDS));

        AtomicReference<Throwable> cleanupFailure = new AtomicReference<>();
        Thread cleaner = new Thread(() -> {
            try {
                cleanupWorker.cleanupExpired();
            } catch (Throwable failure) {
                cleanupFailure.set(failure);
            }
        });
        cleaner.start();
        assertTrue(cleanupStore.candidates.await(5, TimeUnit.SECONDS));

        input.release.countDown();
        appender.join(5_000);
        cleaner.join(5_000);

        assertFalse(appender.isAlive());
        assertFalse(cleaner.isAlive());
        assertNull(appendFailure.get());
        assertNull(cleanupFailure.get());
        assertTrue(harness.sessions.find(created.uploadId()).isPresent());
        assertEquals(1L, Files.size(harness.paths.payloadPath(created.uploadId())));
    }

    private static HarmoniaProperties properties(Path workspace) {
        HarmoniaProperties properties = new HarmoniaProperties();
        properties.setWorkspace(workspace.toString());
        properties.getSourceIngestion().setMaxUploadBytes(10_000_000);
        properties.getSourceIngestion().setMaxHxsBytes(10_000_000);
        properties.getSourceIngestion().setMaxChunkBytes(10_000_000);
        return properties;
    }

    private static Harness harness(Path workspace, Executor executor) {
        HarmoniaProperties properties = properties(workspace);
        JdbcTemplate core = TestDatabases.coreSqlite(workspace);
        SourceSnapshotStore snapshots = new JdbcSourceSnapshotStore(core);
        SourceArtifactRegistry artifacts = new JdbcSourceArtifactRegistry(core);
        SourceUploadSessionStore sessions = new JdbcSourceUploadSessionStore(core);
        SourceArtifactPath paths = new SourceArtifactPath(new WorkspacePaths(properties), properties);
        AtomicInteger atlasCalls = new AtomicInteger();
        AtlasInspection inspection = new AtlasInspection(1, "2026.09", "en", "full", SNAPSHOT_ID,
                CONTENT_ID, "extractor", "lumina", 1, 0, 0);
        SourceSnapshotImporter importer = new SourceSnapshotImporter(atlas(inspection, atlasCalls),
                new com.harmoniasuite.source.hxs.HxsSourceReader(),
                (com.harmoniasuite.source.store.JdbcSourceSnapshotStore) snapshots);
        SourceArtifactStore artifactStore = new FilesystemSourceArtifactStore(paths);
        SourceIngestionWorker worker = new SourceIngestionWorker(sessions, paths, importer, snapshots,
                artifacts, artifactStore, properties, executor);
        SourceUploadService service = new SourceUploadService(
                new SourceSnapshotService(snapshots, artifacts), snapshots, sessions, paths,
                properties, worker);
        return new Harness(properties, core, snapshots, artifacts, sessions, paths, importer,
                artifactStore, worker, service, atlasCalls);
    }

    private static SourceIngestionWorker worker(Harness harness, SourceUploadSessionStore sessions,
                                                Executor executor) {
        return new SourceIngestionWorker(sessions, harness.paths, harness.importer,
                harness.snapshots, harness.artifacts, harness.artifactStore, harness.properties,
                executor);
    }

    private static SourceUploadResponse prepareUpload(Harness harness, Path workspace)
            throws Exception {
        byte[] payload = Files.readAllBytes(createHxs(workspace.resolve("input.hxs")));
        SourceUploadResponse created = harness.service.create(new SourceSnapshotUploadRequest(
                1, "2026.09", "en", "full", SNAPSHOT_ID, CONTENT_ID, "extractor", "lumina",
                1L, 0L, 0L, "identity", (long) payload.length, (long) payload.length));
        harness.service.append(created.uploadId(), 0L, (long) payload.length,
                new ByteArrayInputStream(payload));
        harness.service.complete(created.uploadId());
        return created;
    }

    private static SourceSnapshotUploadRequest request(String encoding, long uploadSize,
                                                        long uncompressedSize) {
        return new SourceSnapshotUploadRequest(1, "2026.09", "en", "full", SNAPSHOT_ID,
                CONTENT_ID, "extractor", "lumina", 1L, 0L, 0L, encoding, uploadSize,
                uncompressedSize);
    }

    private static AtlasClient atlas(AtlasInspection inspection, AtomicInteger calls) {
        return new AtlasClient(null, null, new ObjectMapper()) {
            @Override
            public AtlasInspection inspect(Path path) {
                calls.incrementAndGet();
                return inspection;
            }
        };
    }

    private static Path createHxs(Path path) {
        try {
            javax.sql.DataSource dataSource = com.harmoniasuite.db.SqliteDataSources.create(path);
            JdbcTemplate hxs = new JdbcTemplate(dataSource);
            hxs.execute("""
                    CREATE TABLE hxs_meta (
                        id INTEGER PRIMARY KEY, format_version INTEGER NOT NULL,
                        game_version TEXT NOT NULL, language TEXT NOT NULL, scope TEXT NOT NULL,
                        content_id TEXT NOT NULL, snapshot_id TEXT NOT NULL,
                        extractor_version TEXT NOT NULL, lumina_version TEXT NOT NULL,
                        sheet_count INTEGER NOT NULL, row_count INTEGER NOT NULL,
                        string_cell_count INTEGER NOT NULL)
                    """);
            hxs.execute("""
                    CREATE TABLE sheets (
                        id INTEGER PRIMARY KEY, name TEXT NOT NULL, variant INTEGER NOT NULL,
                        effective_language TEXT NOT NULL, column_count INTEGER NOT NULL,
                        row_count INTEGER NOT NULL, schema_hash BLOB NOT NULL,
                        technical_hash BLOB NOT NULL, string_hash BLOB NOT NULL,
                        content_hash BLOB NOT NULL)
                    """);
            hxs.execute("CREATE TABLE columns (sheet_id INTEGER, column_index INTEGER, offset INTEGER, type INTEGER)");
            hxs.execute("CREATE TABLE \"rows\" (sheet_id INTEGER, row_id INTEGER, subrow_id INTEGER, row_hash BLOB, technical_hash BLOB, string_hash BLOB, technical_payload BLOB)");
            hxs.execute("CREATE TABLE string_cells (sheet_id INTEGER, row_id INTEGER, subrow_id INTEGER, column_index INTEGER, macro_text TEXT, macro_hash BLOB, raw_hash BLOB, raw_value BLOB)");
            hxs.update("INSERT INTO hxs_meta VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", 1, 1,
                    "2026.09", "en", "full", CONTENT_ID, SNAPSHOT_ID, "extractor", "lumina",
                    1, 0, 0);
            hxs.update("INSERT INTO sheets VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", 1, "Empty", 0,
                    "en", 0, 0, hash(1), hash(2), hash(3), hash(4));
            if (dataSource instanceof AutoCloseable closeable) {
                closeable.close();
            }
            return path;
        } catch (Exception exception) {
            throw new IllegalStateException("test HXS could not be created", exception);
        }
    }

    private static byte[] hash(int seed) {
        byte[] value = new byte[32];
        java.util.Arrays.fill(value, (byte) seed);
        return value;
    }

    private record Harness(HarmoniaProperties properties, JdbcTemplate jdbc,
                           SourceSnapshotStore snapshots, SourceArtifactRegistry artifacts,
                           SourceUploadSessionStore sessions, SourceArtifactPath paths,
                           SourceSnapshotImporter importer, SourceArtifactStore artifactStore,
                           SourceIngestionWorker worker, SourceUploadService service,
                           AtomicInteger atlasCalls) {
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> tasks = new java.util.ArrayList<>();
        private boolean reject;

        @Override
        public void execute(Runnable command) {
            if (reject) {
                throw new RejectedExecutionException("test rejection");
            }
            tasks.add(command);
        }

        private void clear() {
            tasks.clear();
        }

        private int size() {
            return tasks.size();
        }

        private void runNext() {
            tasks.remove(0).run();
        }
    }

    private static class DelegatingSessionStore implements SourceUploadSessionStore {
        protected final SourceUploadSessionStore delegate;

        private DelegatingSessionStore(SourceUploadSessionStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public SourceUploadSession create(SourceUploadSession session) {
            return delegate.create(session);
        }

        @Override
        public Optional<SourceUploadSession> find(String uploadId) {
            return delegate.find(uploadId);
        }

        @Override
        public List<SourceUploadSession> findProcessing() {
            return delegate.findProcessing();
        }

        @Override
        public List<SourceUploadSession> findQueued() {
            return delegate.findQueued();
        }

        @Override
        public List<SourceUploadSession> findExpired(SourceUploadState state, long cutoffMs) {
            return delegate.findExpired(state, cutoffMs);
        }

        @Override
        public boolean transition(String uploadId, SourceUploadState expected,
                                  SourceUploadState target) {
            return delegate.transition(uploadId, expected, target);
        }

        @Override
        public boolean requeueProcessing(String uploadId) {
            return delegate.requeueProcessing(uploadId);
        }

        @Override
        public boolean updateReceivedBytes(String uploadId, long receivedBytes) {
            return delegate.updateReceivedBytes(uploadId, receivedBytes);
        }

        @Override
        public boolean setTransportHash(String uploadId, byte[] transportHash) {
            return delegate.setTransportHash(uploadId, transportHash);
        }

        @Override
        public boolean setSnapshotDbId(String uploadId, long snapshotDbId) {
            return delegate.setSnapshotDbId(uploadId, snapshotDbId);
        }

        @Override
        public boolean fail(String uploadId, String errorCode, String errorMessage) {
            return delegate.fail(uploadId, errorCode, errorMessage);
        }

        @Override
        public void delete(String uploadId) {
            delegate.delete(uploadId);
        }
    }

    private static final class RefreshingSessionStore extends DelegatingSessionStore {
        private boolean refreshNextFind;

        private RefreshingSessionStore(SourceUploadSessionStore delegate) {
            super(delegate);
        }

        @Override
        public List<SourceUploadSession> findExpired(SourceUploadState state, long cutoffMs) {
            List<SourceUploadSession> result = super.findExpired(state, cutoffMs);
            refreshNextFind = state == SourceUploadState.UPLOADING && !result.isEmpty();
            return result;
        }

        @Override
        public Optional<SourceUploadSession> find(String uploadId) {
            if (refreshNextFind) {
                refreshNextFind = false;
                SourceUploadSession session = delegate.find(uploadId).orElseThrow();
                delegate.updateReceivedBytes(uploadId, session.receivedBytes());
            }
            return super.find(uploadId);
        }
    }

    private static final class BlockingCleanupStore extends DelegatingSessionStore {
        private final CountDownLatch candidates = new CountDownLatch(1);

        private BlockingCleanupStore(SourceUploadSessionStore delegate) {
            super(delegate);
        }

        @Override
        public List<SourceUploadSession> findExpired(SourceUploadState state, long cutoffMs) {
            List<SourceUploadSession> result = super.findExpired(state, cutoffMs);
            if (state == SourceUploadState.UPLOADING && !result.isEmpty()) {
                candidates.countDown();
            }
            return result;
        }
    }

    private static final class BlockingInputStream extends InputStream {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private boolean served;

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("test input was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("test input was interrupted", exception);
            }
            if (served) {
                return -1;
            }
            served = true;
            buffer[offset] = 7;
            return 1;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }
    }
}
