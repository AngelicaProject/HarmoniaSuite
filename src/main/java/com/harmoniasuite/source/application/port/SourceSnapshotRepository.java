package com.harmoniasuite.source.application.port;

import com.harmoniasuite.source.domain.SourceSheet;
import com.harmoniasuite.source.domain.SourceSnapshot;
import com.harmoniasuite.source.domain.SourceStringCell;
import java.util.List;
import java.util.Optional;

/** Internal query surface for immutable canonical source data. */
public interface SourceSnapshotRepository {

    List<SourceSnapshot> listSnapshots();

    Optional<SourceSnapshot> findBySnapshotId(String snapshotId);

    default boolean existsBySnapshotId(String snapshotId) {
        return findBySnapshotId(snapshotId).isPresent();
    }

    List<SourceSheet> findSheets(String snapshotId);

    Optional<SourceSheet> findSheet(String snapshotId, String sheetName);

    Optional<SourceStringCell> findStringCell(String snapshotId, String sheetName,
                                              long rowId, int subrowId, int columnIndex);

    long countRows(String snapshotId);

    long countStringCells(String snapshotId);
}
