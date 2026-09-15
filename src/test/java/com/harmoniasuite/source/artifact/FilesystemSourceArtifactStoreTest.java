package com.harmoniasuite.source.artifact;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.harmoniasuite.config.HarmoniaProperties;
import com.harmoniasuite.config.WorkspacePaths;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemSourceArtifactStoreTest {

    private static final String SNAPSHOT_ID = "sha256:" + "c".repeat(64);

    @Test
    void storesImmutableZstdArtifactUnderTrustedSnapshotKey(@TempDir Path workspace) throws Exception {
        HarmoniaProperties properties = new HarmoniaProperties();
        properties.setWorkspace(workspace.toString());
        SourceArtifactPath paths = new SourceArtifactPath(new WorkspacePaths(properties), properties);
        FilesystemSourceArtifactStore store = new FilesystemSourceArtifactStore(paths);
        Path hxs = workspace.resolve("managed.hxs");
        byte[] source = new byte[]{0, 1, 2, 3, 4, 5};
        Files.write(hxs, source);
        byte[] hxsHash = SourceCompression.digestFile(hxs, 100).hash();

        StoredSourceArtifact artifact = store.storeHxs(hxs, SNAPSHOT_ID, source.length,
                hxsHash, 3);
        StoredSourceArtifact second = store.storeHxs(hxs, SNAPSHOT_ID, source.length,
                hxsHash, 3);

        assertEquals("sha256/cc/cc/" + "c".repeat(64) + ".hxs.zst", artifact.storageKey());
        assertEquals(artifact.storageKey(), second.storageKey());
        assertEquals(artifact.compressedSize(), second.compressedSize());
        assertArrayEquals(hxsHash, artifact.hxsFileHash());
        assertTrue(Files.isRegularFile(paths.artifactPath(SNAPSHOT_ID)));
        SourceCompression.FileDigest restored = SourceCompression.digestDecompressed(
                paths.artifactPath(SNAPSHOT_ID), source.length, source.length);
        assertArrayEquals(source, Files.readAllBytes(hxs));
        assertArrayEquals(hxsHash, restored.hash());
    }
}
