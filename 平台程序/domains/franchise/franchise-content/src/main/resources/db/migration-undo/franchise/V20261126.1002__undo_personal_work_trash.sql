DO $$
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Work trash rollback requires READ COMMITTED isolation';
    END IF;
    LOCK TABLE fr_content_draft IN ACCESS EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM fr_content_draft WHERE trashed_at IS NOT NULL) THEN
        RAISE EXCEPTION 'Restore works from the trash before removing personal work visibility';
    END IF;
    ALTER TABLE fr_content_draft DROP COLUMN trashed_at, DROP COLUMN library_revision;
END $$;
