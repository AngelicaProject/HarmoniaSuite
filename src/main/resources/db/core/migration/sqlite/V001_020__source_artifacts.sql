CREATE TABLE source_artifacts (
    id INTEGER PRIMARY KEY,
    snapshot_db_id INTEGER NOT NULL UNIQUE REFERENCES source_snapshots(id) ON DELETE CASCADE,
    storage_key TEXT NOT NULL UNIQUE,
    compression TEXT NOT NULL CHECK (compression = 'zstd'),
    uncompressed_size INTEGER NOT NULL CHECK (uncompressed_size >= 0),
    compressed_size INTEGER NOT NULL CHECK (compressed_size >= 0),
    hxs_file_hash BLOB NOT NULL CHECK (length(hxs_file_hash) = 32),
    artifact_hash BLOB NOT NULL CHECK (length(artifact_hash) = 32),
    created_at_ms INTEGER NOT NULL
);

CREATE TABLE source_upload_sessions (
    upload_id TEXT PRIMARY KEY,
    state TEXT NOT NULL CHECK (state IN (
        'UPLOADING', 'QUEUED', 'VERIFYING', 'MATERIALIZING', 'STORING', 'COMPLETED', 'FAILED'
    )),
    transport_encoding TEXT NOT NULL CHECK (transport_encoding IN ('identity', 'zstd')),
    upload_size INTEGER NOT NULL CHECK (upload_size > 0),
    uncompressed_size INTEGER NOT NULL CHECK (uncompressed_size > 0),
    received_bytes INTEGER NOT NULL CHECK (received_bytes >= 0 AND received_bytes <= upload_size),
    claimed_hxs_version INTEGER NOT NULL CHECK (claimed_hxs_version = 1),
    claimed_game_version TEXT NOT NULL,
    claimed_language TEXT NOT NULL,
    claimed_scope TEXT NOT NULL CHECK (claimed_scope = 'full'),
    claimed_snapshot_id TEXT NOT NULL,
    claimed_content_id TEXT NOT NULL,
    claimed_extractor_version TEXT NOT NULL,
    claimed_lumina_version TEXT NOT NULL,
    claimed_sheet_count INTEGER NOT NULL CHECK (claimed_sheet_count >= 0),
    claimed_row_count INTEGER NOT NULL CHECK (claimed_row_count >= 0),
    claimed_string_cell_count INTEGER NOT NULL CHECK (claimed_string_cell_count >= 0),
    transport_hash BLOB CHECK (transport_hash IS NULL OR length(transport_hash) = 32),
    snapshot_db_id INTEGER REFERENCES source_snapshots(id) ON DELETE SET NULL,
    error_code TEXT,
    error_message TEXT,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL
);

CREATE INDEX idx_source_upload_sessions_state ON source_upload_sessions(state);
