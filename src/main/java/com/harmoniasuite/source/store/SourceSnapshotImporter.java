package com.harmoniasuite.source.store;

import com.harmoniasuite.source.atlas.AtlasClient;
import com.harmoniasuite.source.atlas.AtlasInspection;
import com.harmoniasuite.source.hxs.HxsSourceReader;

import java.nio.file.Path;
import java.util.Objects;

/** Application-level source import workflow with Atlas inspection first. */
public final class SourceSnapshotImporter {

    private final AtlasClient atlasClient;
    private final HxsSourceReader reader;
    private final JdbcSourceSnapshotStore store;

    public SourceSnapshotImporter(AtlasClient atlasClient, HxsSourceReader reader,
                                  JdbcSourceSnapshotStore store) {
        this.atlasClient = Objects.requireNonNull(atlasClient, "atlasClient");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Opaque Atlas-verified artifact handle. Its constructor is intentionally private. */
    public static final class VerifiedHxs {
        private final Path path;
        private final AtlasInspection inspection;

        private VerifiedHxs(Path path, AtlasInspection inspection) {
            this.path = path;
            this.inspection = inspection;
        }

        public Path path() {
            return path;
        }

        public AtlasInspection inspection() {
            return inspection;
        }
    }

    /** Inspect a managed HXS exactly once and return an opaque trusted handle. */
    public VerifiedHxs verify(Path hxsPath) {
        Objects.requireNonNull(hxsPath, "hxsPath");
        Path managedPath = hxsPath.toAbsolutePath().normalize();
        AtlasInspection inspection = atlasClient.inspect(managedPath);
        return new VerifiedHxs(managedPath, inspection);
    }

    /** Import the exact artifact that produced the Atlas verification. */
    public SourceSnapshot importVerified(VerifiedHxs verified) {
        Objects.requireNonNull(verified, "verified");
        return store.importVerified(verified, reader);
    }

    /** Inspect with Atlas, then ingest the same server-managed artifact. */
    public SourceSnapshot importSnapshot(Path hxsPath) {
        return importVerified(verify(hxsPath));
    }
}
