UPDATE entries SET status = 'untranslated' WHERE status = 'new';
UPDATE entry_history SET old_status = 'untranslated' WHERE old_status = 'new';
UPDATE entry_history SET new_status = 'untranslated' WHERE new_status = 'new';
