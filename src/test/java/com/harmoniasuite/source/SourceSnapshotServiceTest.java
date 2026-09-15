package com.harmoniasuite.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.harmoniasuite.dto.SourceSnapshotPreflightRequest;
import com.harmoniasuite.dto.SourceSnapshotPreflightResponse;
import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import com.harmoniasuite.exception.HarmoniaSuiteConflictException;
import com.harmoniasuite.source.store.SourceSheet;
import com.harmoniasuite.source.store.SourceSnapshot;
import com.harmoniasuite.source.store.SourceSnapshotStore;
import com.harmoniasuite.source.store.SourceStringCell;
import com.harmoniasuite.service.source.SourceSnapshotService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SourceSnapshotServiceTest {

    private static final String SNAPSHOT_ID = "sha256:" + "a".repeat(64);
    private static final String CONTENT_ID = "sha256:" + "b".repeat(64);

    private InMemoryStore store;
    private SourceSnapshotService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        service = new SourceSnapshotService(store);
    }

    @Test
    void missingSnapshotRequiresUploadWithoutPersistingClientMetadata() {
        SourceSnapshotPreflightResponse response = service.preflight(request());

        assertEquals("UPLOAD_REQUIRED", response.status());
        assertEquals(SNAPSHOT_ID, response.snapshotId());
        assertEquals(null, response.snapshot());
        assertEquals(0, store.snapshots.size());
    }

    @Test
    void matchingSnapshotIsAvailableWithStoredMetadata() {
        SourceSnapshot stored = snapshot();
        store.snapshots.add(stored);

        SourceSnapshotPreflightResponse response = service.preflight(request());

        assertEquals("AVAILABLE", response.status());
        assertEquals(null, response.snapshotId());
        assertEquals(stored.contentId(), response.snapshot().contentId());
        assertEquals(stored.gameVersion(), response.snapshot().gameVersion());
        assertEquals(stored.stringCellCount(), response.snapshot().stringCellCount());
    }

    @Test
    void conflictingMetadataReturnsConflict() {
        store.snapshots.add(snapshot());
        SourceSnapshotPreflightRequest conflicting = new SourceSnapshotPreflightRequest(
                1, "different-game", "en", "full", SNAPSHOT_ID, CONTENT_ID,
                "extractor-test", "lumina-test", 2L, 3L, 3L);

        assertThrows(HarmoniaSuiteConflictException.class,
                () -> service.preflight(conflicting));
    }

    @Test
    void invalidRequestMetadataIsRejected() {
        assertThrows(HarmoniaSuiteBadRequestException.class,
                () -> service.preflight(new SourceSnapshotPreflightRequest(
                        2, "7.2.0", "en", "full", SNAPSHOT_ID, CONTENT_ID,
                        "extractor-test", "lumina-test", 2L, 3L, 3L)));
        assertThrows(HarmoniaSuiteBadRequestException.class,
                () -> service.preflight(new SourceSnapshotPreflightRequest(
                        1, "7.2.0", "en", "partial", SNAPSHOT_ID, CONTENT_ID,
                        "extractor-test", "lumina-test", 2L, 3L, 3L)));
        assertThrows(HarmoniaSuiteBadRequestException.class,
                () -> service.preflight(new SourceSnapshotPreflightRequest(
                        1, "7.2.0", "en", "full", "sha256:bad", CONTENT_ID,
                        "extractor-test", "lumina-test", 2L, 3L, 3L)));
        assertThrows(HarmoniaSuiteBadRequestException.class,
                () -> service.preflight(new SourceSnapshotPreflightRequest(
                        1, "7.2.0", "en", "full", SNAPSHOT_ID, CONTENT_ID,
                        "extractor-test", "lumina-test", -1L, 3L, 3L)));
    }

    @Test
    void listAndDetailReadOnlySurfaceUsesStore() {
        store.snapshots.add(snapshot());

        assertEquals(1, service.listSnapshots().size());
        assertEquals(SNAPSHOT_ID, service.getSnapshot(SNAPSHOT_ID).snapshotId());
    }

    private static SourceSnapshotPreflightRequest request() {
        return new SourceSnapshotPreflightRequest(
                1, "7.2.0", "en", "full", SNAPSHOT_ID, CONTENT_ID,
                "extractor-test", "lumina-test", 2L, 3L, 3L);
    }

    private static SourceSnapshot snapshot() {
        return new SourceSnapshot(7, SNAPSHOT_ID, CONTENT_ID, 1, "7.2.0", "en", "full",
                "extractor-test", "lumina-test", 2, 3, 3);
    }

    private static final class InMemoryStore implements SourceSnapshotStore {
        private final List<SourceSnapshot> snapshots = new java.util.ArrayList<>();

        @Override
        public List<SourceSnapshot> listSnapshots() {
            return List.copyOf(snapshots);
        }

        @Override
        public Optional<SourceSnapshot> findBySnapshotId(String snapshotId) {
            return snapshots.stream().filter(snapshot -> snapshot.snapshotId().equals(snapshotId))
                    .findFirst();
        }

        @Override
        public List<SourceSheet> findSheets(String snapshotId) {
            return List.of();
        }

        @Override
        public Optional<SourceSheet> findSheet(String snapshotId, String sheetName) {
            return Optional.empty();
        }

        @Override
        public Optional<SourceStringCell> findStringCell(String snapshotId, String sheetName,
                                                          long rowId, int subrowId,
                                                          int columnIndex) {
            return Optional.empty();
        }

        @Override
        public long countRows(String snapshotId) {
            return 0;
        }

        @Override
        public long countStringCells(String snapshotId) {
            return 0;
        }
    }
}
