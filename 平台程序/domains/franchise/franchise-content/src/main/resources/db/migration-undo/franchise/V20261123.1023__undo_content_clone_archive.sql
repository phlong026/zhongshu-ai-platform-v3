DO $$
BEGIN
    LOCK TABLE fr_content_digital_clone IN ACCESS EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM fr_content_digital_clone WHERE archived_at IS NOT NULL) THEN
        RAISE EXCEPTION '分身或声音已归档，禁止删除归档标记';
    END IF;
    ALTER TABLE fr_content_digital_clone DROP COLUMN archived_at;
END $$;
