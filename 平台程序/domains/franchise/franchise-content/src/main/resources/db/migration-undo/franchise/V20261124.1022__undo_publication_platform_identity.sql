-- One statement: a refused rollback must preserve the current uniqueness protection even under autocommit.
DO $$
BEGIN
    LOCK TABLE fr_content_publication IN SHARE ROW EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM fr_content_publication WHERE status <> 'failed'
               GROUP BY work_id, account_id HAVING COUNT(*) > 1) THEN
        RAISE EXCEPTION 'Cannot roll back publication platform identity while cross-platform records exist';
    END IF;
    DROP INDEX uk_fr_content_publication_active;
    CREATE UNIQUE INDEX uk_fr_content_publication_active
        ON fr_content_publication (work_id, account_id)
        WHERE status <> 'failed';
END $$;
