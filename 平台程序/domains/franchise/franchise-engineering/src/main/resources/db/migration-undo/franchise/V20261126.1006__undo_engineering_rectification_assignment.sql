DO $$
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Rectification assignment rollback requires READ COMMITTED isolation';
    END IF;
    LOCK TABLE fr_eng_rectification, fr_eng_rectification_event IN ACCESS EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM fr_eng_rectification WHERE owner_user_id IS NOT NULL OR due_date IS NOT NULL)
       OR EXISTS (SELECT 1 FROM fr_eng_rectification_event WHERE owner_user_id IS NOT NULL OR due_date IS NOT NULL) THEN
        RAISE EXCEPTION 'RECTIFICATION_ASSIGNMENT_IN_USE: preserve recorded responsibilities, deadlines and handling history';
    END IF;
    DROP INDEX idx_eng_rectification_event_history;
    ALTER TABLE fr_eng_rectification_event DROP COLUMN due_date, DROP COLUMN owner_user_id;
    ALTER TABLE fr_eng_rectification DROP COLUMN due_date, DROP COLUMN owner_user_id;
END $$;
