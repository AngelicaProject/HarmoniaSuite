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

    /** Inspect with Atlas, then ingest the same server-managed artifact. */
    public SourceSnapshot importSnapshot(Path hxsPath) {
        AtlasInspection inspection = atlasClient.inspect(hxsPath);
        return importSnapshot(hxsPath, inspection);
    }

    /** Package-private trusted seam for importer tests and internal staged-artifact callers. */
    SourceSnapshot importSnapshot(Path hxsPath, AtlasInspection trustedInspection) {
        return store.importSnapshot(hxsPath, trustedInspection, reader);
    }
}
