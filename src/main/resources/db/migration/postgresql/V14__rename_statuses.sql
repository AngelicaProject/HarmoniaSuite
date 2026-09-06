UPDATE entries SET status = 'needs_human_review' WHERE status = 'human_review';
UPDATE entries SET status = 'no_translation_required' WHERE status = 'no_translation';
UPDATE entry_history SET old_status = 'needs_human_review' WHERE old_status = 'human_review';
UPDATE entry_history SET old_status = 'no_translation_required' WHERE old_status = 'no_translation';
UPDATE entry_history SET new_status = 'needs_human_review' WHERE new_status = 'human_review';
UPDATE entry_history SET new_status = 'no_translation_required' WHERE new_status = 'no_translation';
