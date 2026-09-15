package com.harmoniasuite.source.infrastructure.hxs;

import com.harmoniasuite.db.SqliteDataSources;
import com.harmoniasuite.source.domain.SourceSnapshotMetadata;
import java.nio.file.Path;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Handwritten builder for the exact physical HXS v1 schema used by Atlas artifacts. */
public final class HxsV1TestFixture {

    private final Path path;
    private final JdbcTemplate jdbc;

    private HxsV1TestFixture(Path path, JdbcTemplate jdbc) {
        this.path = path;
        this.jdbc = jdbc;
    }

    public static HxsV1TestFixture create(Path path, SourceSnapshotMetadata metadata)
            throws Exception {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(metadata, "metadata");
        HxsV1TestFixture fixture = new HxsV1TestFixture(path,
                new JdbcTemplate(SqliteDataSources.create(path)));
        fixture.createSchema();
        fixture.insertMetadata(metadata);
        return fixture;
    }

    public static Path createEmpty(Path path, SourceSnapshotMetadata metadata) throws Exception {
        return create(path, metadata).path;
    }

    public Path path() {
        return path;
    }

    public HxsV1TestFixture sheet(long id, String name, int variant, String effectiveLanguage,
                                  long columnCount, long rowCount, byte[] schemaHash,
                                  byte[] technicalHash, byte[] stringHash, byte[] contentHash) {
        jdbc.update("INSERT INTO sheets VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, name, variant,
                effectiveLanguage, columnCount, rowCount, schemaHash, technicalHash, stringHash,
                contentHash);
        return this;
    }

    public HxsV1TestFixture column(long sheetId, int columnIndex, long offset, int type) {
        jdbc.update("INSERT INTO columns VALUES (?, ?, ?, ?)", sheetId, columnIndex, offset, type);
        return this;
    }

    public HxsV1TestFixture row(long sheetId, long rowId, int subrowId, byte[] rowHash,
                                byte[] technicalHash, byte[] stringHash, byte[] payload) {
        jdbc.update("INSERT INTO \"rows\" VALUES (?, ?, ?, ?, ?, ?, ?)", sheetId, rowId,
                subrowId, rowHash, technicalHash, stringHash, payload);
        return this;
    }

    public HxsV1TestFixture stringCell(long sheetId, long rowId, int subrowId, int columnIndex,
                                       String macroText, byte[] macroHash, byte[] rawHash,
                                       byte[] rawValue) {
        jdbc.update("INSERT INTO string_cells VALUES (?, ?, ?, ?, ?, ?, ?, ?)", sheetId, rowId,
                subrowId, columnIndex, macroText, macroHash, rawHash, rawValue);
        return this;
    }

    private void createSchema() {
        jdbc.execute("""
                CREATE TABLE hxs_meta (
                    id INTEGER PRIMARY KEY, format_version INTEGER NOT NULL,
                    game_version TEXT NOT NULL, language TEXT NOT NULL, scope TEXT NOT NULL,
                    content_id TEXT NOT NULL, snapshot_id TEXT NOT NULL,
                    extractor_version TEXT NOT NULL, lumina_version TEXT NOT NULL,
                    sheet_count INTEGER NOT NULL, row_count INTEGER NOT NULL,
                    string_cell_count INTEGER NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE sheets (
                    id INTEGER PRIMARY KEY, name TEXT NOT NULL, variant INTEGER NOT NULL,
                    effective_language TEXT NOT NULL, column_count INTEGER NOT NULL,
                    row_count INTEGER NOT NULL, schema_hash BLOB NOT NULL,
                    technical_hash BLOB NOT NULL, string_hash BLOB NOT NULL,
                    content_hash BLOB NOT NULL
                )
                """);
        jdbc.execute("CREATE TABLE columns (sheet_id INTEGER, column_index INTEGER, offset INTEGER, type INTEGER)");
        jdbc.execute("""
                CREATE TABLE "rows" (
                    sheet_id INTEGER, row_id INTEGER, subrow_id INTEGER,
                    row_hash BLOB, technical_hash BLOB, string_hash BLOB,
                    technical_payload BLOB
                )
                """);
        jdbc.execute("""
                CREATE TABLE string_cells (
                    sheet_id INTEGER, row_id INTEGER, subrow_id INTEGER, column_index INTEGER,
                    macro_text TEXT, macro_hash BLOB, raw_hash BLOB, raw_value BLOB
                )
                """);
    }

    private void insertMetadata(SourceSnapshotMetadata metadata) {
        jdbc.update("""
                INSERT INTO hxs_meta VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, 1, metadata.hxsVersion(), metadata.gameVersion(), metadata.language(),
                metadata.scope(), metadata.contentId().value(), metadata.snapshotId().value(),
                metadata.extractorVersion(), metadata.luminaVersion(), metadata.sheetCount(),
                metadata.rowCount(), metadata.stringCellCount());
    }
}
