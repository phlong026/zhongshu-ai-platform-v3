CREATE TABLE fr_content_publisher_account (
    id BIGINT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    platform VARCHAR(32) NOT NULL CHECK (platform IN ('douyin', 'channels')),
    display_name VARCHAR(64) NOT NULL,
    identifier VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_fr_content_publisher_identity UNIQUE (company_id, platform, identifier)
);
CREATE INDEX idx_fr_content_publisher_owner ON fr_content_publisher_account(company_id, owner_user_id, platform);
ALTER TABLE fr_content_publication ADD COLUMN publisher_account_id BIGINT NULL
    REFERENCES fr_content_publisher_account(id);
COMMENT ON COLUMN fr_content_publication.publisher_account_id IS 'Stable user-registered identity; NULL keeps historical manual registration distinct';
DROP INDEX uk_fr_content_publication_active;
CREATE UNIQUE INDEX uk_fr_content_publication_active ON fr_content_publication(work_id, platform, account_id)
    WHERE status <> 'failed' AND publisher_account_id IS NULL;
CREATE UNIQUE INDEX uk_fr_content_publication_account ON fr_content_publication(work_id, publisher_account_id)
    WHERE status <> 'failed' AND publisher_account_id IS NOT NULL;
