package com.harmoniasuite.source.infrastructure.config;

import com.harmoniasuite.config.CoreDatabase;
import com.harmoniasuite.config.WorkspacePaths;
import com.harmoniasuite.source.application.SourceIngestionMaintenanceService;
import com.harmoniasuite.source.application.SourceIngestionProcessor;
import com.harmoniasuite.source.application.SourceSnapshotImportService;
import com.harmoniasuite.source.application.SourceSnapshotService;
import com.harmoniasuite.source.application.SourceUploadService;
import com.harmoniasuite.source.application.model.SourceIngestionPropertiesView;
import com.harmoniasuite.source.application.port.SourceArtifactRepository;
import com.harmoniasuite.source.application.port.SourceArtifactStorage;
import com.harmoniasuite.source.application.port.SourceIngestionDispatcher;
import com.harmoniasuite.source.application.port.SourceSnapshotMaterializer;
import com.harmoniasuite.source.application.port.SourceSnapshotRepository;
import com.harmoniasuite.source.application.port.SourceUploadSessionRepository;
import com.harmoniasuite.source.application.port.SourceUploadStagingStorage;
import com.harmoniasuite.source.infrastructure.atlas.AtlasClient;
import com.harmoniasuite.source.infrastructure.hxs.HxsSourceReader;
import com.harmoniasuite.source.infrastructure.persistence.JdbcSourceArtifactRepository;
import com.harmoniasuite.source.infrastructure.persistence.JdbcSourceSnapshotMaterializer;
import com.harmoniasuite.source.infrastructure.persistence.JdbcSourceSnapshotRepository;
import com.harmoniasuite.source.infrastructure.persistence.JdbcSourceUploadSessionRepository;
import com.harmoniasuite.source.infrastructure.storage.FilesystemSourceArtifactStorage;
import com.harmoniasuite.source.infrastructure.storage.FilesystemSourceUploadStagingStorage;
import com.harmoniasuite.source.infrastructure.storage.SourceArtifactPath;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit wiring for canonical source ports and infrastructure adapters. */
@Configuration
public class SourceInfrastructureConfiguration {

    @Bean
    public Clock sourceClock() {
        return Clock.systemUTC();
    }

    @Bean
    public HxsSourceReader hxsSourceReader() {
        return new HxsSourceReader();
    }

    @Bean
    public JdbcSourceSnapshotRepository jdbcSourceSnapshotRepository(CoreDatabase database) {
        return new JdbcSourceSnapshotRepository(database.jdbc());
    }

    @Bean
    public SourceSnapshotMaterializer sourceSnapshotMaterializer(
            JdbcSourceSnapshotRepository repository, HxsSourceReader reader) {
        return new JdbcSourceSnapshotMaterializer(repository, reader);
    }

    @Bean
    public SourceSnapshotImportService sourceSnapshotImportService(AtlasClient atlas,
                                                                    SourceSnapshotMaterializer materializer) {
        return new SourceSnapshotImportService(atlas, materializer);
    }

    @Bean
    public SourceArtifactPath sourceArtifactPath(WorkspacePaths workspace,
                                                 SourceIngestionProperties properties) {
        return new SourceArtifactPath(workspace, properties);
    }

    @Bean
    public JdbcSourceArtifactRepository jdbcSourceArtifactRepository(CoreDatabase database) {
        return new JdbcSourceArtifactRepository(database.jdbc());
    }

    @Bean
    public SourceArtifactStorage sourceArtifactStorage(SourceArtifactPath paths) {
        return new FilesystemSourceArtifactStorage(paths);
    }

    @Bean
    public JdbcSourceUploadSessionRepository jdbcSourceUploadSessionRepository(CoreDatabase database,
                                                                                Clock clock) {
        return new JdbcSourceUploadSessionRepository(database.jdbc(), clock);
    }

    @Bean
    public SourceUploadStagingStorage sourceUploadStagingStorage(SourceArtifactPath paths) {
        return new FilesystemSourceUploadStagingStorage(paths);
    }

    @Bean
    public SourceIngestionPropertiesView sourceIngestionPropertiesView(
            SourceIngestionProperties properties) {
        return new SourceIngestionPropertiesView(properties.getMaxUploadBytes(),
                properties.getMaxHxsBytes(), properties.getMaxChunkBytes(), properties.getZstdLevel());
    }

    @Bean
    public SourceSnapshotService sourceSnapshotService(SourceSnapshotRepository snapshots,
                                                       SourceArtifactRepository artifacts) {
        return new SourceSnapshotService(snapshots, artifacts);
    }

    @Bean
    public SourceUploadService sourceUploadService(SourceSnapshotService snapshots,
                                                   SourceUploadSessionRepository sessions,
                                                   SourceUploadStagingStorage staging,
                                                   SourceIngestionPropertiesView limits,
                                                   SourceIngestionDispatcher dispatcher,
                                                   Clock clock) {
        return new SourceUploadService(snapshots, sessions, staging, limits, dispatcher, clock);
    }

    @Bean
    public SourceIngestionProcessor sourceIngestionProcessor(
            SourceUploadSessionRepository sessions, SourceUploadStagingStorage staging,
            SourceSnapshotImportService importer, SourceArtifactRepository artifacts,
            SourceArtifactStorage artifactStorage, SourceIngestionPropertiesView limits,
            Clock clock) {
        return new SourceIngestionProcessor(sessions, staging, importer, artifacts,
                artifactStorage, limits, clock);
    }

    @Bean
    public SourceIngestionMaintenanceService sourceIngestionMaintenanceService(
            SourceUploadSessionRepository sessions, SourceUploadStagingStorage staging,
            SourceIngestionDispatcher dispatcher, SourceIngestionProperties properties,
            Clock clock) {
        return new SourceIngestionMaintenanceService(sessions, staging, dispatcher,
                properties.getStaleUploadHours(), clock);
    }
}
