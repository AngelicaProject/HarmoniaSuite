package com.harmoniasuite.source.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.harmoniasuite.source.api.dto.SourceSnapshotPreflightRequest;
import com.harmoniasuite.source.api.dto.SourceSnapshotPreflightResponse;
import com.harmoniasuite.source.application.SourceSnapshotService;
import com.harmoniasuite.source.application.port.SourceArtifactRepository;
import com.harmoniasuite.source.application.port.SourceSnapshotRepository;
import com.harmoniasuite.source.domain.SourceArtifact;
import com.harmoniasuite.source.domain.SourceSheet;
import com.harmoniasuite.source.domain.SourceSnapshot;
import com.harmoniasuite.source.domain.SourceStringCell;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SourceSnapshotControllerTest {

    @Test
    void preflightMapsApiRequestToApplicationMetadata() {
        SourceSnapshotService service = new SourceSnapshotService(new EmptySnapshots(),
                new EmptyArtifacts());
        SourceApiMapper mapper = new SourceApiMapperImpl();
        String snapshotId = "sha256:" + "a".repeat(64);
        String contentId = "sha256:" + "b".repeat(64);

        SourceSnapshotPreflightResponse response = new SourceSnapshotController(service, mapper)
                .preflight(new SourceSnapshotPreflightRequest(1, "7.2", "en", "full",
                        snapshotId, contentId, "extractor", "lumina", 0L, 0L, 0L));

        assertEquals("UPLOAD_REQUIRED", response.status());
        assertEquals(snapshotId, response.snapshotId());
    }

    private static final class EmptySnapshots implements SourceSnapshotRepository {
        public List<SourceSnapshot> listSnapshots() { return List.of(); }
        public Optional<SourceSnapshot> findBySnapshotId(String id) { return Optional.empty(); }
        public List<SourceSheet> findSheets(String id) { return List.of(); }
        public Optional<SourceSheet> findSheet(String a, String b) { return Optional.empty(); }
        public Optional<SourceStringCell> findStringCell(String a, String b, long c, int d, int e) { return Optional.empty(); }
        public long countRows(String id) { return 0; }
        public long countStringCells(String id) { return 0; }
    }

    private static final class EmptyArtifacts implements SourceArtifactRepository {
        public Optional<SourceArtifact> findBySnapshotId(String id) { return Optional.empty(); }
        public Optional<SourceArtifact> findBySnapshotDbId(long id) { return Optional.empty(); }
        public SourceArtifact register(SourceArtifact artifact) { return artifact; }
    }
}
