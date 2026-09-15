package com.harmoniasuite.source.infrastructure.persistence;

import com.harmoniasuite.source.application.port.SourceSnapshotMaterializer;
import com.harmoniasuite.source.domain.SourceSnapshot;
import com.harmoniasuite.source.domain.SourceSnapshotMetadata;
import com.harmoniasuite.source.infrastructure.hxs.HxsSourceReader;
import java.nio.file.Path;
import java.util.Objects;

/** Infrastructure adapter that keeps import streaming and transaction handling behind a port. */
public final class JdbcSourceSnapshotMaterializer implements SourceSnapshotMaterializer {

    private final JdbcSourceSnapshotRepository repository;
    private final HxsSourceReader reader;

    public JdbcSourceSnapshotMaterializer(JdbcSourceSnapshotRepository repository,
                                          HxsSourceReader reader) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    @Override
    public SourceSnapshot materialize(Path managedHxsPath, SourceSnapshotMetadata trustedMetadata) {
        return repository.importSnapshot(managedHxsPath, trustedMetadata, reader);
    }
}
