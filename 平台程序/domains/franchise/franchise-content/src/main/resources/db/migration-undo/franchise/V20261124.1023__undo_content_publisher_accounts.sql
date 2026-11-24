-- Preserve newly registered identities and publication attribution; only an unused feature can roll back.
DO $$
BEGIN
    LOCK TABLE fr_content_publisher_account, fr_content_publication IN SHARE ROW EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM fr_content_publisher_account)
       OR EXISTS (SELECT 1 FROM fr_content_publication WHERE publisher_account_id IS NOT NULL) THEN
        RAISE EXCEPTION 'Cannot roll back publishing accounts while registered identities or publications exist';
    END IF;
    DROP INDEX uk_fr_content_publication_account;
    DROP INDEX uk_fr_content_publication_active;
    CREATE UNIQUE INDEX uk_fr_content_publication_active ON fr_content_publication(work_id, platform, account_id)
        WHERE status <> 'failed';
    ALTER TABLE fr_content_publication DROP COLUMN publisher_account_id;
    DROP TABLE fr_content_publisher_account;
END $$;
