CREATE TABLE IF NOT EXISTS entry_stats (
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    status TEXT NOT NULL,
    entries_count BIGINT NOT NULL DEFAULT 0,
    translated_count BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (project_id, status),
    CHECK (entries_count >= 0),
    CHECK (translated_count >= 0),
    CHECK (translated_count <= entries_count)
);

ALTER TABLE entries ADD COLUMN translated_flag INTEGER GENERATED ALWAYS AS (
    CASE
        WHEN status = 'no_translation_required' THEN 1
        WHEN status <> 'stale' AND NULLIF(TRIM(translation), '') IS NOT NULL THEN 1
        ELSE 0
    END
) STORED;

DELETE FROM entry_stats;
INSERT INTO entry_stats (project_id, status, entries_count, translated_count)
SELECT project_id, status, COUNT(*), COALESCE(SUM(translated_flag), 0)
FROM entries
GROUP BY project_id, status;

DROP TRIGGER IF EXISTS entries_stats_after_change ON entries;
DROP FUNCTION IF EXISTS update_entry_stats();
CREATE OR REPLACE FUNCTION update_entry_stats() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO entry_stats (project_id, status, entries_count, translated_count)
        VALUES (NEW.project_id, NEW.status, 1, NEW.translated_flag)
        ON CONFLICT (project_id, status) DO UPDATE SET
            entries_count = entry_stats.entries_count + EXCLUDED.entries_count,
            translated_count = entry_stats.translated_count + EXCLUDED.translated_count;
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        UPDATE entry_stats
        SET entries_count = entries_count - 1,
            translated_count = translated_count - OLD.translated_flag
        WHERE project_id = OLD.project_id AND status = OLD.status;
        DELETE FROM entry_stats
        WHERE project_id = OLD.project_id
          AND status = OLD.status
          AND entries_count = 0;
        RETURN OLD;
    ELSE
        IF OLD.status = NEW.status THEN
            UPDATE entry_stats
            SET translated_count = translated_count - OLD.translated_flag + NEW.translated_flag
            WHERE project_id = OLD.project_id AND status = OLD.status;
        ELSE
            UPDATE entry_stats
            SET entries_count = entries_count - 1,
                translated_count = translated_count - OLD.translated_flag
            WHERE project_id = OLD.project_id AND status = OLD.status;

            INSERT INTO entry_stats (project_id, status, entries_count, translated_count)
            VALUES (NEW.project_id, NEW.status, 1, NEW.translated_flag)
            ON CONFLICT (project_id, status) DO UPDATE SET
                entries_count = entry_stats.entries_count + EXCLUDED.entries_count,
                translated_count = entry_stats.translated_count + EXCLUDED.translated_count;
            DELETE FROM entry_stats
            WHERE project_id = OLD.project_id
              AND status = OLD.status
              AND entries_count = 0;
        END IF;
        RETURN NEW;
    END IF;
END;
$$;

CREATE TRIGGER entries_stats_after_change
AFTER INSERT OR UPDATE OF translation, status OR DELETE ON entries
FOR EACH ROW EXECUTE FUNCTION update_entry_stats();
