-- Prefer rolling back application code while retaining additive columns.
-- This destructive undo is permitted only before any T26-10 facts have been delivered.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM user_message WHERE fact_version IS NOT NULL OR action_type<>'NONE') THEN
        RAISE EXCEPTION 'T26-10 messages exist; retain additive schema and recover application code only';
    END IF;
END $$;
DROP INDEX IF EXISTS idx_user_message_actions;
DROP INDEX IF EXISTS uk_user_message_fact_recipient;
ALTER TABLE user_message DROP CONSTRAINT IF EXISTS ck_message_fact_identity;
ALTER TABLE user_message DROP CONSTRAINT IF EXISTS ck_message_action_type;
ALTER TABLE user_message DROP COLUMN fact_snapshot, DROP COLUMN target_snapshot,
    DROP COLUMN action_type, DROP COLUMN occurred_at, DROP COLUMN fact_version;
