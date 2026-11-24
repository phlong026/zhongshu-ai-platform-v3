-- Manual account names can be identical on different platforms.
DROP INDEX uk_fr_content_publication_active;
CREATE UNIQUE INDEX uk_fr_content_publication_active
    ON fr_content_publication (work_id, platform, account_id)
    WHERE status <> 'failed';
