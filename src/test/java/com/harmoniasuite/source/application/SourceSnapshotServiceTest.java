package com.harmoniasuite.source.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.harmoniasuite.source.application.port.SourceArtifactRepository;
import com.harmoniasuite.source.application.port.SourceSnapshotRepository;
import com.harmoniasuite.source.domain.SourceSnapshot;
import com.harmoniasuite.source.domain.SourceSnapshotAvailability;
import com.harmoniasuite.source.domain.SourceSnapshotMetadata;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SourceSnapshotServiceTest {

    @Test
    void preflightIsAvailableOnlyWhenMetadataAndArtifactMatch() {
        SourceSnapshot snapshot = snapshot();
        SourceSnapshotService service = new SourceSnapshotService(new Repository(snapshot),
                new ArtifactRepository(true));

        assertEquals(SourceSnapshotAvailability.AVAILABLE,
                service.preflight(snapshot.metadata()).status());
        assertEquals(SourceSnapshotAvailability.UPLOAD_REQUIRED,
                new SourceSnapshotService(new Repository(snapshot), new ArtifactRepository(false))
                        .preflight(snapshot.metadata()).status());
    }

    @Test
    void conflictingMetadataIsRejected() {
        SourceSnapshot snapshot = snapshot();
        SourceSnapshotMetadata mismatch = new SourceSnapshotMetadata(1, "7.3", "en", "full",
                snapshot.snapshotId(), snapshot.contentId(), snapshot.extractorVersion(),
                snapshot.luminaVersion(), snapshot.sheetCount(), snapshot.rowCount(), snapshot.stringCellCount());
        SourceSnapshotService service = new SourceSnapshotService(new Repository(snapshot),
                new ArtifactRepository(true));

        assertThrows(RuntimeException.class, () -> service.preflight(mismatch));
    }

    private static SourceSnapshot snapshot() {
        return new SourceSnapshot(1, "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64),
                1, "7.2", "en", "full", "extractor", "lumina", 0, 0, 0);
    }

    private record Repository(SourceSnapshot snapshot) implements SourceSnapshotRepository {
        public List<SourceSnapshot> listSnapshots() { return List.of(snapshot); }
        public Optional<SourceSnapshot> findBySnapshotId(String id) { return Optional.of(snapshot); }
        public List<com.harmoniasuite.source.domain.SourceSheet> findSheets(String id) { return List.of(); }
        public Optional<com.harmoniasuite.source.domain.SourceSheet> findSheet(String a, String b) { return Optional.empty(); }
        public Optional<com.harmoniasuite.source.domain.SourceStringCell> findStringCell(String a, String b, long c, int d, int e) { return Optional.empty(); }
        public long countRows(String id) { return 0; }
        public long countStringCells(String id) { return 0; }
    }

    private record ArtifactRepository(boolean present) implements SourceArtifactRepository {
        public Optional<com.harmoniasuite.source.domain.SourceArtifact> findBySnapshotId(String id) { return Optional.empty(); }
        public Optional<com.harmoniasuite.source.domain.SourceArtifact> findBySnapshotDbId(long id) { return present ? Optional.of(mockArtifact()) : Optional.empty(); }
        public com.harmoniasuite.source.domain.SourceArtifact register(com.harmoniasuite.source.domain.SourceArtifact artifact) { return artifact; }
        private static com.harmoniasuite.source.domain.SourceArtifact mockArtifact() { return new com.harmoniasuite.source.domain.SourceArtifact(1, 1, "a.hxs.zst", "zstd", 1, 1, new byte[32], new byte[32], 0); }
    }
}
