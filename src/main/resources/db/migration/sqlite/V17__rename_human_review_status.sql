UPDATE entries
SET status = 'human_reviewed'
WHERE status = 'needs_human_review';

UPDATE entry_history
SET old_status = 'human_reviewed'
WHERE old_status = 'needs_human_review';

UPDATE entry_history
SET new_status = 'human_reviewed'
WHERE new_status = 'needs_human_review';
