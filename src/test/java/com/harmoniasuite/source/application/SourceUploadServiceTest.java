package com.harmoniasuite.source.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.harmoniasuite.source.application.command.CreateSourceUploadCommand;
import com.harmoniasuite.source.api.dto.CreateSourceUploadRequest;
import com.harmoniasuite.source.application.model.SourceIngestionPropertiesView;
import com.harmoniasuite.source.application.model.SourceUploadSession;
import com.harmoniasuite.source.application.port.SourceArtifactRepository;
import com.harmoniasuite.source.application.port.SourceIngestionDispatcher;
import com.harmoniasuite.source.application.port.SourceSnapshotRepository;
import com.harmoniasuite.source.application.port.SourceUploadSessionRepository;
import com.harmoniasuite.source.application.port.SourceUploadStagingStorage;
import com.harmoniasuite.source.domain.Sha256Digest;
import com.harmoniasuite.source.domain.SourceSnapshotMetadata;
import com.harmoniasuite.source.domain.SourceUploadState;
import com.harmoniasuite.source.domain.TransportEncoding;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import jakarta.validation.Validation;

class SourceUploadServiceTest {

    @Test
    void createAppendAndCompleteUseTypedSessionAndClock() {
        InMemorySessions sessions = new InMemorySessions();
        InMemoryStaging staging = new InMemoryStaging();
        List<UUID> dispatched = new ArrayList<>();
        SourceUploadService service = service(sessions, staging, dispatched::add);
        var created = service.create(new CreateSourceUploadCommand(metadata(), "identity", 3, 3));

        UUID uploadId = created.upload().uploadId();
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), created.upload().createdAt());
        service.append(uploadId, 0L, 3L, new ByteArrayInputStream(new byte[] {1, 2, 3}));
        service.complete(uploadId);

        assertEquals(List.of(uploadId), dispatched);
        assertEquals(SourceUploadState.QUEUED, sessions.find(uploadId).orElseThrow().state());
    }

    @Test
    void identityEncodingIsRejectedAtApiBoundary() {
        CreateSourceUploadRequest request = new CreateSourceUploadRequest(
                1, "7.2", "en", "full", "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64), "extractor", "lumina", 0L, 0L, 0L,
                "identity", 2L, 3L);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertFalse(factory.getValidator().validate(request).isEmpty());
        }
    }

    private static SourceUploadService service(InMemorySessions sessions, InMemoryStaging staging,
                                               SourceIngestionDispatcher dispatcher) {
        SourceSnapshotService snapshots = new SourceSnapshotService(new EmptySnapshots(),
                new EmptyArtifacts());
        return new SourceUploadService(snapshots, sessions, staging,
                new SourceIngestionPropertiesView(100, 100, 100, 3), dispatcher,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    private static SourceSnapshotMetadata metadata() {
        return new SourceSnapshotMetadata(1, "7.2", "en", "full",
                "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64), "extractor", "lumina", 0, 0, 0);
    }

    private static final class EmptySnapshots implements SourceSnapshotRepository {
        public List<com.harmoniasuite.source.domain.SourceSnapshot> listSnapshots() { return List.of(); }
        public Optional<com.harmoniasuite.source.domain.SourceSnapshot> findBySnapshotId(String id) { return Optional.empty(); }
        public List<com.harmoniasuite.source.domain.SourceSheet> findSheets(String id) { return List.of(); }
        public Optional<com.harmoniasuite.source.domain.SourceSheet> findSheet(String a, String b) { return Optional.empty(); }
        public Optional<com.harmoniasuite.source.domain.SourceStringCell> findStringCell(String a, String b, long c, int d, int e) { return Optional.empty(); }
        public long countRows(String id) { return 0; }
        public long countStringCells(String id) { return 0; }
    }
    private static final class EmptyArtifacts implements SourceArtifactRepository {
        public Optional<com.harmoniasuite.source.domain.SourceArtifact> findBySnapshotId(String id) { return Optional.empty(); }
        public Optional<com.harmoniasuite.source.domain.SourceArtifact> findBySnapshotDbId(long id) { return Optional.empty(); }
        public com.harmoniasuite.source.domain.SourceArtifact register(com.harmoniasuite.source.domain.SourceArtifact artifact) { return artifact; }
    }
    private static final class InMemorySessions implements SourceUploadSessionRepository {
        private final java.util.Map<UUID, SourceUploadSession> values = new java.util.HashMap<>();
        public SourceUploadSession create(SourceUploadSession session) { values.put(session.uploadId(), session); return session; }
        public Optional<SourceUploadSession> find(UUID id) { return Optional.ofNullable(values.get(id)); }
        public List<SourceUploadSession> findProcessing() { return List.of(); }
        public List<SourceUploadSession> findQueued() { return List.of(); }
        public List<SourceUploadSession> findExpired(SourceUploadState state, Instant cutoff) { return List.of(); }
        public boolean transition(UUID id, SourceUploadState expected, SourceUploadState target) { var s=values.get(id); if(s==null||s.state()!=expected)return false; values.put(id,new SourceUploadSession(s.uploadId(),target,s.transportEncoding(),s.uploadSize(),s.uncompressedSize(),s.receivedBytes(),s.metadata(),s.transportHash(),s.snapshotDbId(),s.errorCode(),s.errorMessage(),s.createdAt(),s.updatedAt())); return true; }
        public boolean requeueProcessing(UUID id) { return false; }
        public boolean updateReceivedBytes(UUID id,long bytes) { var s=values.get(id); values.put(id,new SourceUploadSession(s.uploadId(),s.state(),s.transportEncoding(),s.uploadSize(),s.uncompressedSize(),bytes,s.metadata(),s.transportHash(),s.snapshotDbId(),s.errorCode(),s.errorMessage(),s.createdAt(),s.updatedAt())); return true; }
        public boolean setTransportHash(UUID id,Sha256Digest hash) { return true; }
        public boolean setSnapshotDbId(UUID id,long value) { return true; }
        public boolean fail(UUID id,com.harmoniasuite.source.domain.SourceUploadErrorCode code,String message) { return false; }
        public void delete(UUID id) { values.remove(id); }
    }
    private static final class InMemoryStaging implements SourceUploadStagingStorage {
        private final java.util.Map<UUID, ByteArrayOutputStream> values = new java.util.HashMap<>();
        public void create(UUID id) { values.put(id,new ByteArrayOutputStream()); }
        public long receivedBytes(UUID id) { return values.get(id).size(); }
        public boolean exists(UUID id) { return values.containsKey(id); }
        public long append(UUID id,InputStream body,long length,long max,long declared) throws IOException { body.transferTo(values.get(id)); return values.get(id).size(); }
        public void delete(UUID id) { values.remove(id); }
        public Path directory(UUID id) { return Path.of(id.toString()); }
        public Path payload(UUID id) { return directory(id).resolve("payload"); }
        public Path snapshot(UUID id) { return directory(id).resolve("snapshot"); }
        public Path snapshotTemp(UUID id) { return directory(id).resolve("temp"); }
        public void deleteFileIfPresent(Path path) { }
        public com.harmoniasuite.source.application.model.PreparedSourceUpload prepareForIngestion(UUID a,TransportEncoding b,long c,long d,long e,long f) { throw new UnsupportedOperationException(); }
    }
}
