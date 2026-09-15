package com.harmoniasuite.config;

import com.harmoniasuite.source.atlas.AtlasClient;
import com.harmoniasuite.source.hxs.HxsSourceReader;
import com.harmoniasuite.source.store.JdbcSourceSnapshotStore;
import com.harmoniasuite.source.store.SourceSnapshotImporter;
import com.harmoniasuite.source.artifact.JdbcSourceArtifactRegistry;
import com.harmoniasuite.source.artifact.SourceArtifactPath;
import com.harmoniasuite.source.artifact.SourceArtifactRegistry;
import com.harmoniasuite.source.upload.JdbcSourceUploadSessionStore;
import com.harmoniasuite.source.upload.SourceUploadSessionStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit Spring wiring for canonical source infrastructure. */
@Configuration
public class CoreSourceStoreConfig {

    @Bean
    public HxsSourceReader hxsSourceReader() {
        return new HxsSourceReader();
    }

    @Bean
    public JdbcSourceSnapshotStore sourceSnapshotStore(CoreDatabase coreDatabase) {
        return new JdbcSourceSnapshotStore(coreDatabase.jdbc());
    }

    @Bean
    public SourceSnapshotImporter sourceSnapshotImporter(AtlasClient atlasClient,
                                                         HxsSourceReader reader,
                                                         JdbcSourceSnapshotStore store) {
        return new SourceSnapshotImporter(atlasClient, reader, store);
    }

    @Bean
    public SourceArtifactPath sourceArtifactPath(WorkspacePaths workspace,
                                                 HarmoniaProperties properties) {
        return new SourceArtifactPath(workspace, properties);
    }

    @Bean
    public SourceArtifactRegistry sourceArtifactRegistry(CoreDatabase coreDatabase) {
        return new JdbcSourceArtifactRegistry(coreDatabase.jdbc());
    }

    @Bean
    public SourceUploadSessionStore sourceUploadSessionStore(CoreDatabase coreDatabase) {
        return new JdbcSourceUploadSessionStore(coreDatabase.jdbc());
    }
}
