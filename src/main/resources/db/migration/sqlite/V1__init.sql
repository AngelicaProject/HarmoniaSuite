CREATE TABLE IF NOT EXISTS projects (
    id UUID PRIMARY KEY DEFAULT (uuid6()),
    slug TEXT NOT NULL UNIQUE,
    input_root TEXT NOT NULL DEFAULT '',
    project_dir TEXT NOT NULL DEFAULT '',
    output_dir TEXT NOT NULL DEFAULT '',
    source_locale TEXT NOT NULL DEFAULT 'en',
    target_locale TEXT NOT NULL DEFAULT 'ru',
    created_at TEXT NOT NULL DEFAULT '',
    updated_at TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS source_files (
    id UUID PRIMARY KEY DEFAULT (uuid6()),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    path TEXT NOT NULL,
    selected_translate INTEGER NOT NULL DEFAULT 0,
    selected_export INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT '',
    updated_at TEXT NOT NULL DEFAULT '',
    UNIQUE (project_id, path)
);
CREATE INDEX IF NOT EXISTS idx_source_files_project ON source_files(project_id);

CREATE TABLE IF NOT EXISTS entries (
    id UUID PRIMARY KEY DEFAULT (uuid6()),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    file_id UUID NOT NULL REFERENCES source_files(id) ON DELETE CASCADE,
    cell_id TEXT NOT NULL,
    file_path TEXT NOT NULL DEFAULT '',
    row_key TEXT NOT NULL DEFAULT '',
    column_index INTEGER NOT NULL DEFAULT 0,
    column_name TEXT NOT NULL DEFAULT '',
    row_index INTEGER NOT NULL DEFAULT 0,
    source TEXT NOT NULL DEFAULT '',
    source_lc TEXT NOT NULL DEFAULT '',
    translation TEXT NOT NULL DEFAULT '',
    translation_lc TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'new',
    created_at TEXT NOT NULL DEFAULT '',
    updated_at TEXT NOT NULL DEFAULT '',
    UNIQUE (project_id, cell_id)
);
CREATE INDEX IF NOT EXISTS idx_entries_project_status ON entries(project_id, status);
CREATE INDEX IF NOT EXISTS idx_entries_project_file ON entries(project_id, file_id);
CREATE INDEX IF NOT EXISTS idx_entries_project_file_status ON entries(project_id, file_id, status);
CREATE INDEX IF NOT EXISTS idx_entries_project_source ON entries(project_id, source);

CREATE TABLE IF NOT EXISTS entry_history (
    id UUID PRIMARY KEY DEFAULT (uuid6()),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    entry_id UUID NOT NULL REFERENCES entries(id) ON DELETE CASCADE,
    old_translation TEXT NOT NULL DEFAULT '',
    old_status TEXT NOT NULL DEFAULT '',
    new_translation TEXT NOT NULL DEFAULT '',
    new_status TEXT NOT NULL DEFAULT '',
    origin TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL DEFAULT '',
    updated_at TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_entry_history_entry ON entry_history(project_id, entry_id);

CREATE TABLE IF NOT EXISTS pack (
    id UUID PRIMARY KEY DEFAULT (uuid6()),
    project_id UUID NOT NULL UNIQUE REFERENCES projects(id) ON DELETE CASCADE,
    pack_id TEXT NOT NULL DEFAULT '',
    translation_version TEXT NOT NULL DEFAULT '',
    game_version TEXT NOT NULL DEFAULT '',
    vendor_id TEXT NOT NULL DEFAULT '',
    vendor_name TEXT NOT NULL DEFAULT '',
    vendor_url TEXT NOT NULL DEFAULT '',
    vendor_contact TEXT NOT NULL DEFAULT '',
    title TEXT NOT NULL DEFAULT '',
    description TEXT NOT NULL DEFAULT '',
    changelog TEXT NOT NULL DEFAULT '',
    homepage TEXT NOT NULL DEFAULT '',
    license TEXT NOT NULL DEFAULT '',
    min_plugin_version TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL DEFAULT '',
    updated_at TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS pack_authors (
    id UUID PRIMARY KEY DEFAULT (uuid6()),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name TEXT NOT NULL DEFAULT '',
    role TEXT NOT NULL DEFAULT '',
    contact TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL DEFAULT '',
    updated_at TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_pack_authors_project ON pack_authors(project_id);

CREATE TABLE IF NOT EXISTS pack_languages (
    id UUID PRIMARY KEY DEFAULT (uuid6()),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    lang TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT '',
    updated_at TEXT NOT NULL DEFAULT '',
    UNIQUE (project_id, lang)
);

CREATE TABLE IF NOT EXISTS pack_compatible_versions (
    id UUID PRIMARY KEY DEFAULT (uuid6()),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    version TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT '',
    updated_at TEXT NOT NULL DEFAULT '',
    UNIQUE (project_id, version)
);

CREATE TABLE IF NOT EXISTS merge_runs (
    id UUID PRIMARY KEY DEFAULT (uuid6()),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    started_at TEXT NOT NULL DEFAULT '',
    finished_at TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'running',
    input_root TEXT NOT NULL DEFAULT '',
    output_root TEXT NOT NULL DEFAULT '',
    files_total INTEGER NOT NULL DEFAULT 0,
    files_processed INTEGER NOT NULL DEFAULT 0,
    translated_cells INTEGER NOT NULL DEFAULT 0,
    result_json TEXT NOT NULL DEFAULT '[]',
    created_at TEXT NOT NULL DEFAULT '',
    updated_at TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_merge_runs_project ON merge_runs(project_id);

CREATE TABLE IF NOT EXISTS project_stats (
    id UUID PRIMARY KEY DEFAULT (uuid6()),
    project_id UUID NOT NULL UNIQUE REFERENCES projects(id) ON DELETE CASCADE,
    files_count INTEGER NOT NULL DEFAULT 0,
    entries_count INTEGER NOT NULL DEFAULT 0,
    translated_count INTEGER NOT NULL DEFAULT 0,
    by_status_json TEXT NOT NULL DEFAULT '{}',
    created_at TEXT NOT NULL DEFAULT '',
    updated_at TEXT NOT NULL DEFAULT ''
);
