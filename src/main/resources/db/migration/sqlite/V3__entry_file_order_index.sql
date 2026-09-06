CREATE INDEX IF NOT EXISTS idx_entries_project_file_order
    ON entries(project_id, file_path, row_index, column_index);
