package com.harmoniasuite.service.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
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

    private static HarmoniaProperties properties(Path workspace) {
        HarmoniaProperties properties = new HarmoniaProperties();
        properties.setWorkspace(workspace.toString());
        properties.getSourceIngestion().setMaxUploadBytes(10_000_000);
        properties.getSourceIngestion().setMaxHxsBytes(10_000_000);
        properties.getSourceIngestion().setMaxChunkBytes(10_000_000);
        return properties;
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
}
