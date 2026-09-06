ALTER TABLE source_files ADD COLUMN total INTEGER NOT NULL DEFAULT 0;
ALTER TABLE source_files ADD COLUMN done INTEGER NOT NULL DEFAULT 0;
UPDATE source_files
SET total = (
    SELECT COUNT(*)
    FROM entries e
    WHERE e.project_id = source_files.project_id
    AND e.file_id = source_files.id
),
done = (
    SELECT COALESCE(SUM(CASE WHEN TRIM(e.translation) <> '' AND e.status <> 'stale' THEN 1 ELSE 0 END), 0)
    FROM entries e
    WHERE e.project_id = source_files.project_id
    AND e.file_id = source_files.id
);
