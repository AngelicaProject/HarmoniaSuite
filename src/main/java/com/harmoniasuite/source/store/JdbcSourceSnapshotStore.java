package com.harmoniasuite.source.store;

import com.harmoniasuite.source.atlas.AtlasInspection;
import com.harmoniasuite.source.hxs.HxsColumn;
import com.harmoniasuite.source.hxs.HxsMetadata;
import com.harmoniasuite.source.hxs.HxsRow;
import com.harmoniasuite.source.hxs.HxsSheet;
import com.harmoniasuite.source.hxs.HxsSourceReader;
import com.harmoniasuite.source.hxs.HxsSourceSink;
import com.harmoniasuite.source.hxs.HxsStringCell;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** JDBC implementation of the canonical source store and its atomic HXS importer. */
public final class JdbcSourceSnapshotStore implements SourceSnapshotStore {

    static final int BATCH_SIZE = 2_000;
    private static final int MAX_BUSY_RETRIES = 5;
    private static final ConcurrentHashMap<String, Object> SNAPSHOT_LOCKS = new ConcurrentHashMap<>();

    private static final String SNAPSHOT_COLUMNS = "id, snapshot_id, content_id, hxs_version, "
            + "game_version, language, scope, extractor_version, lumina_version, sheet_count, "
            + "row_count, string_cell_count";
    private static final String SHEET_COLUMNS = "s.id, s.snapshot_db_id, s.name, s.variant, "
            + "s.effective_language, s.column_count, s.row_count, s.schema_hash, "
            + "s.technical_hash, s.string_hash, s.content_hash";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcSourceSnapshotStore(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    public JdbcSourceSnapshotStore(JdbcTemplate jdbcTemplate) {
        this.jdbc = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        DataSource dataSource = Objects.requireNonNull(jdbcTemplate.getDataSource(),
                "jdbcTemplate.dataSource");
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /** Imports through a reader supplied by the trusted ingestion boundary. */
    SourceSnapshot importSnapshot(Path hxsPath, AtlasInspection trustedInspection,
                                  HxsSourceReader reader) {
        Objects.requireNonNull(hxsPath, "hxsPath");
        Objects.requireNonNull(trustedInspection, "trustedInspection");
        Objects.requireNonNull(reader, "reader");

        String snapshotId = trustedInspection.snapshotId();
        Object lock = SNAPSHOT_LOCKS.computeIfAbsent(snapshotId, ignored -> new Object());
        try {
            synchronized (lock) {
                try {
                    SourceSnapshot imported = importWithBusyRetry(hxsPath, trustedInspection, reader);
                    return Objects.requireNonNull(imported, "imported snapshot");
                } catch (DuplicateSnapshotRace race) {
                    SourceSnapshot existing = findBySnapshotId(snapshotId)
                            .orElseThrow(() -> new SourceSnapshotImportException(
                                    "snapshot uniqueness race completed without a stored snapshot", race));
                    validateImmutableMetadata(existing, trustedInspection);
                    return existing;
                }
            }
        } finally {
            SNAPSHOT_LOCKS.remove(snapshotId, lock);
        }
    }

    private SourceSnapshot importWithBusyRetry(Path hxsPath, AtlasInspection trustedInspection,
                                               HxsSourceReader reader) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.execute(status -> {
                    ImportSink sink = new ImportSink(trustedInspection);
                    reader.read(hxsPath, trustedInspection, sink);
                    return sink.result();
                });
            } catch (DataAccessException exception) {
                if (attempt >= MAX_BUSY_RETRIES || !isBusyFailure(exception)) {
                    throw exception;
                }
                try {
                    Thread.sleep(25L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SourceSnapshotImportException(
                            "snapshot import was interrupted while waiting for the database", interrupted);
                }
            }
        }
    }

    @Override
    public Optional<SourceSnapshot> findBySnapshotId(String snapshotId) {
        Objects.requireNonNull(snapshotId, "snapshotId");
        List<SourceSnapshot> rows = jdbc.query(
                "SELECT " + SNAPSHOT_COLUMNS + " FROM source_snapshots WHERE snapshot_id = ?",
                snapshotMapper(), snapshotId);
        return rows.stream().findFirst();
    }

    @Override
    public List<SourceSheet> findSheets(String snapshotId) {
        Objects.requireNonNull(snapshotId, "snapshotId");
        return jdbc.query("""
                SELECT %s
                FROM source_sheets s
                JOIN source_snapshots p ON p.id = s.snapshot_db_id
                WHERE p.snapshot_id = ?
                ORDER BY s.name
                """.formatted(SHEET_COLUMNS), sheetMapper(), snapshotId);
    }

    @Override
    public Optional<SourceSheet> findSheet(String snapshotId, String sheetName) {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(sheetName, "sheetName");
        List<SourceSheet> rows = jdbc.query("""
                SELECT %s
                FROM source_sheets s
                JOIN source_snapshots p ON p.id = s.snapshot_db_id
                WHERE p.snapshot_id = ? AND s.name = ?
                """.formatted(SHEET_COLUMNS), sheetMapper(), snapshotId, sheetName);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<SourceStringCell> findStringCell(String snapshotId, String sheetName,
                                                     long rowId, int subrowId, int columnIndex) {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(sheetName, "sheetName");
        List<SourceStringCell> rows = jdbc.query("""
                SELECT c.id, c.sheet_id, c.row_id, c.subrow_id, c.column_index,
                       c.macro_text, c.macro_hash, c.raw_hash
                FROM source_string_cells c
                JOIN source_sheets s ON s.id = c.sheet_id
                JOIN source_snapshots p ON p.id = s.snapshot_db_id
                WHERE p.snapshot_id = ? AND s.name = ? AND c.row_id = ?
                  AND c.subrow_id = ? AND c.column_index = ?
                """, stringCellMapper(), snapshotId, sheetName, rowId, subrowId, columnIndex);
        return rows.stream().findFirst();
    }

    @Override
    public long countRows(String snapshotId) {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM source_rows r
                JOIN source_sheets s ON s.id = r.sheet_id
                JOIN source_snapshots p ON p.id = s.snapshot_db_id
                WHERE p.snapshot_id = ?
                """, Long.class, snapshotId);
        return Objects.requireNonNull(count, "row count");
    }

    @Override
    public long countStringCells(String snapshotId) {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM source_string_cells c
                JOIN source_sheets s ON s.id = c.sheet_id
                JOIN source_snapshots p ON p.id = s.snapshot_db_id
                WHERE p.snapshot_id = ?
                """, Long.class, snapshotId);
        return Objects.requireNonNull(count, "String cell count");
    }

    private long insertSnapshot(AtlasInspection inspection) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO source_snapshots
                            (snapshot_id, content_id, hxs_version, game_version, language, scope,
                             extractor_version, lumina_version, sheet_count, row_count, string_cell_count)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, inspection.snapshotId());
                statement.setString(2, inspection.contentId());
                statement.setInt(3, inspection.hxsVersion());
                statement.setString(4, inspection.gameVersion());
                statement.setString(5, inspection.language());
                statement.setString(6, inspection.scope());
                statement.setString(7, inspection.extractorVersion());
                statement.setString(8, inspection.luminaVersion());
                statement.setLong(9, inspection.sheetCount());
                statement.setLong(10, inspection.rowCount());
                statement.setLong(11, inspection.stringCellCount());
                return statement;
            }, keyHolder);
        } catch (DataAccessException exception) {
            if (isSnapshotUniquenessViolation(exception)) {
                throw new DuplicateSnapshotRace(exception);
            }
            throw exception;
        }
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new SourceSnapshotImportException("database did not return source snapshot ID");
        }
        return key.longValue();
    }

    private long insertSheet(long snapshotDbId, HxsSheet sheet) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO source_sheets
                        (snapshot_db_id, name, variant, effective_language, column_count, row_count,
                         schema_hash, technical_hash, string_hash, content_hash)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, snapshotDbId);
            statement.setString(2, sheet.name());
            statement.setInt(3, sheet.variant());
            statement.setString(4, sheet.effectiveLanguage());
            statement.setLong(5, sheet.columnCount());
            statement.setLong(6, sheet.rowCount());
            statement.setBytes(7, sheet.schemaHash());
            statement.setBytes(8, sheet.technicalHash());
            statement.setBytes(9, sheet.stringHash());
            statement.setBytes(10, sheet.contentHash());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new SourceSnapshotImportException("database did not return source sheet ID");
        }
        return key.longValue();
    }

    private void insertColumns(long sheetId, List<SourceColumn> columns) {
        if (columns.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO source_columns (sheet_id, column_index, offset, type)
                VALUES (?, ?, ?, ?)
                """, columns, BATCH_SIZE, (statement, column) -> {
            statement.setLong(1, column.sheetId());
            statement.setInt(2, column.columnIndex());
            statement.setLong(3, column.offset());
            statement.setInt(4, column.type());
        });
    }

    private void insertRows(List<SourceRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO source_rows
                    (sheet_id, row_id, subrow_id, row_hash, technical_hash, string_hash)
                VALUES (?, ?, ?, ?, ?, ?)
                """, rows, BATCH_SIZE, (statement, row) -> {
            statement.setLong(1, row.sheetId());
            statement.setLong(2, row.rowId());
            statement.setInt(3, row.subrowId());
            statement.setBytes(4, row.rowHash());
            statement.setBytes(5, row.technicalHash());
            statement.setBytes(6, row.stringHash());
        });
    }

    private void insertStringCells(List<SourceStringCell> cells) {
        if (cells.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO source_string_cells
                    (sheet_id, row_id, subrow_id, column_index, macro_text, macro_hash, raw_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, cells, BATCH_SIZE, (statement, cell) -> {
            statement.setLong(1, cell.sheetId());
            statement.setLong(2, cell.rowId());
            statement.setInt(3, cell.subrowId());
            statement.setInt(4, cell.columnIndex());
            statement.setString(5, cell.macroText());
            statement.setBytes(6, cell.macroHash());
            statement.setBytes(7, cell.rawHash());
        });
    }

    private static void validateImmutableMetadata(SourceSnapshot stored, AtlasInspection inspection) {
        boolean matches = stored.hxsVersion() == inspection.hxsVersion()
                && stored.snapshotId().equals(inspection.snapshotId())
                && stored.contentId().equals(inspection.contentId())
                && stored.gameVersion().equals(inspection.gameVersion())
                && stored.language().equals(inspection.language())
                && stored.scope().equals(inspection.scope())
                && stored.extractorVersion().equals(inspection.extractorVersion())
                && stored.luminaVersion().equals(inspection.luminaVersion())
                && stored.sheetCount() == inspection.sheetCount()
                && stored.rowCount() == inspection.rowCount()
                && stored.stringCellCount() == inspection.stringCellCount();
        if (!matches) {
            throw new SourceSnapshotImportException(
                    "stored source snapshot metadata conflicts with the trusted Atlas inspection");
        }
    }

    private static boolean isSnapshotUniquenessViolation(DataAccessException exception) {
        if (exception instanceof DuplicateKeyException) {
            return true;
        }
        String message = exception.getMessage();
        return message != null && message.toLowerCase().contains("snapshot_id")
                && message.toLowerCase().contains("unique");
    }

    private static boolean isBusyFailure(DataAccessException exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("sqlite_busy") || lower.contains("database is locked")
                        || lower.contains("database table is locked")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private RowMapper<SourceSnapshot> snapshotMapper() {
        return (resultSet, rowNum) -> new SourceSnapshot(
                resultSet.getLong("id"), resultSet.getString("snapshot_id"),
                resultSet.getString("content_id"), resultSet.getInt("hxs_version"),
                resultSet.getString("game_version"), resultSet.getString("language"),
                resultSet.getString("scope"), resultSet.getString("extractor_version"),
                resultSet.getString("lumina_version"), resultSet.getLong("sheet_count"),
                resultSet.getLong("row_count"), resultSet.getLong("string_cell_count"));
    }

    private RowMapper<SourceSheet> sheetMapper() {
        return (resultSet, rowNum) -> new SourceSheet(
                resultSet.getLong("id"), resultSet.getLong("snapshot_db_id"),
                resultSet.getString("name"), resultSet.getInt("variant"),
                resultSet.getString("effective_language"), resultSet.getLong("column_count"),
                resultSet.getLong("row_count"), resultSet.getBytes("schema_hash"),
                resultSet.getBytes("technical_hash"), resultSet.getBytes("string_hash"),
                resultSet.getBytes("content_hash"));
    }

    private static RowMapper<SourceStringCell> stringCellMapper() {
        return (resultSet, rowNum) -> new SourceStringCell(
                resultSet.getLong("id"), resultSet.getLong("sheet_id"),
                resultSet.getLong("row_id"), resultSet.getInt("subrow_id"),
                resultSet.getInt("column_index"), resultSet.getString("macro_text"),
                resultSet.getBytes("macro_hash"), resultSet.getBytes("raw_hash"));
    }

    private final class ImportSink implements HxsSourceSink {

        private enum SheetPhase {
            NONE,
            ROWS,
            STRING_CELLS
        }

        private final AtlasInspection inspection;
        private final List<SourceColumn> columns = new ArrayList<>();
        private final List<SourceRow> rows = new ArrayList<>(BATCH_SIZE);
        private final List<SourceStringCell> stringCells = new ArrayList<>(BATCH_SIZE);
        private SourceSnapshot existing;
        private SourceSnapshot inserted;
        private HxsSheet currentSheet;
        private long currentSheetDbId;
        private long observedSheets;
        private long observedRows;
        private long observedStringCells;
        private long currentSheetRows;
        private long currentSheetStringCells;
        private SheetPhase phase = SheetPhase.NONE;

        private ImportSink(AtlasInspection inspection) {
            this.inspection = inspection;
        }

        @Override
        public boolean begin(HxsMetadata metadata) {
            if (!metadata.snapshotId().equals(inspection.snapshotId())) {
                throw new SourceSnapshotImportException("HXS metadata snapshot ID changed before import");
            }
            existing = findBySnapshotId(inspection.snapshotId()).orElse(null);
            if (existing != null) {
                validateImmutableMetadata(existing, inspection);
                return false;
            }
            long id = insertSnapshot(inspection);
            inserted = new SourceSnapshot(id, inspection.snapshotId(), inspection.contentId(),
                    inspection.hxsVersion(), inspection.gameVersion(), inspection.language(),
                    inspection.scope(), inspection.extractorVersion(), inspection.luminaVersion(),
                    inspection.sheetCount(), inspection.rowCount(), inspection.stringCellCount());
            return true;
        }

        @Override
        public void beginSheet(HxsSheet sheet) {
            if (currentSheet != null) {
                throw new SourceSnapshotImportException("HXS emitted a sheet before closing the previous sheet");
            }
            currentSheet = sheet;
            currentSheetDbId = insertSheet(inserted.id(), sheet);
            columns.clear();
            rows.clear();
            stringCells.clear();
            currentSheetRows = 0;
            currentSheetStringCells = 0;
            phase = SheetPhase.ROWS;
            observedSheets++;
        }

        @Override
        public void column(HxsColumn column) {
            requireCurrentSheet();
            columns.add(new SourceColumn(currentSheetDbId, column.columnIndex(), column.offset(), column.type()));
        }

        @Override
        public void row(HxsRow row) {
            requireCurrentSheet();
            requirePhase(SheetPhase.ROWS, "row");
            rows.add(new SourceRow(currentSheetDbId, row.rowId(), row.subrowId(), row.rowHash(),
                    row.technicalHash(), row.stringHash()));
            currentSheetRows++;
            observedRows++;
            if (rows.size() >= BATCH_SIZE) {
                insertRows(rows);
                rows.clear();
            }
        }

        @Override
        public void rowsComplete() {
            requireCurrentSheet();
            requirePhase(SheetPhase.ROWS, "rowsComplete");
            insertRows(rows);
            rows.clear();
            phase = SheetPhase.STRING_CELLS;
        }

        @Override
        public void stringCell(HxsStringCell cell) {
            requireCurrentSheet();
            requirePhase(SheetPhase.STRING_CELLS, "stringCell");
            stringCells.add(new SourceStringCell(0, currentSheetDbId, cell.rowId(), cell.subrowId(),
                    cell.columnIndex(), cell.macroText(), cell.macroHash(), cell.rawHash()));
            currentSheetStringCells++;
            observedStringCells++;
            if (stringCells.size() >= BATCH_SIZE) {
                insertStringCells(stringCells);
                stringCells.clear();
            }
        }

        @Override
        public void endSheet() {
            requireCurrentSheet();
            requirePhase(SheetPhase.STRING_CELLS, "endSheet");
            insertColumns(currentSheetDbId, columns);
            insertStringCells(stringCells);
            stringCells.clear();
            if (columns.size() != currentSheet.columnCount()) {
                throw new SourceSnapshotImportException("sheet column count mismatch for " + currentSheet.name());
            }
            if (currentSheetRows != currentSheet.rowCount()) {
                throw new SourceSnapshotImportException("sheet row count mismatch for " + currentSheet.name());
            }
            currentSheet = null;
            phase = SheetPhase.NONE;
            columns.clear();
        }

        @Override
        public void end() {
            if (currentSheet != null) {
                throw new SourceSnapshotImportException("HXS ended while a sheet was open");
            }
            if (observedSheets != inspection.sheetCount()) {
                throw new SourceSnapshotImportException("global sheet count mismatch");
            }
            if (observedRows != inspection.rowCount()) {
                throw new SourceSnapshotImportException("global row count mismatch");
            }
            if (observedStringCells != inspection.stringCellCount()) {
                throw new SourceSnapshotImportException("global String cell count mismatch");
            }
        }

        private SourceSnapshot result() {
            if (existing != null) {
                return existing;
            }
            if (inserted == null) {
                throw new SourceSnapshotImportException("HXS reader did not emit import metadata");
            }
            return inserted;
        }

        private void requireCurrentSheet() {
            if (currentSheet == null) {
                throw new SourceSnapshotImportException("HXS emitted a record outside a sheet");
            }
        }

        private void requirePhase(SheetPhase expected, String event) {
            if (phase != expected) {
                throw new SourceSnapshotImportException(
                        "HXS emitted " + event + " during " + phase.name().toLowerCase()
                                + " phase; expected " + expected.name().toLowerCase() + " phase");
            }
        }
    }

    private static final class DuplicateSnapshotRace extends RuntimeException {

        private DuplicateSnapshotRace(Throwable cause) {
            super(cause);
        }
    }
}
