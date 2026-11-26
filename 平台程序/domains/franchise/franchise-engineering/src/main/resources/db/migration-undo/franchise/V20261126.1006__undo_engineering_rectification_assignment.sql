DROP INDEX IF EXISTS idx_eng_rectification_event_history;
ALTER TABLE fr_eng_rectification_event DROP COLUMN IF EXISTS due_date, DROP COLUMN IF EXISTS owner_user_id;
ALTER TABLE fr_eng_rectification DROP COLUMN IF EXISTS due_date, DROP COLUMN IF EXISTS owner_user_id;
