-- Preserve idempotency evidence if any generation exists, including an uncertain external outcome.
DO $$ BEGIN
    LOCK TABLE fr_content_cover_request IN ACCESS EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM fr_content_cover_request) THEN
        RAISE EXCEPTION 'Cover generation records exist; retain the table and reconcile them before rollback';
    END IF;
    DROP TABLE fr_content_cover_request;
END $$;
