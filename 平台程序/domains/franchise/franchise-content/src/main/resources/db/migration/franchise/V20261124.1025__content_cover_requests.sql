-- Keep creation intent and its receipt across client retries; never re-send an uncertain provider submission.
CREATE TABLE fr_content_cover_request (
    id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    author_user_id BIGINT NOT NULL,
    author_post_id BIGINT NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    template_id VARCHAR(200) NOT NULL,
    title VARCHAR(200) NOT NULL,
    canvas_preset VARCHAR(4) NOT NULL CHECK (canvas_preset IN ('9:16', '3:4')),
    status VARCHAR(24) NOT NULL CHECK (status IN ('PROCESSING', 'READY', 'FAILED', 'UNCERTAIN', 'ARCHIVE_PENDING')),
    provider_cover_id VARCHAR(200),
    provider_url VARCHAR(4096),
    cover_asset_id BIGINT,
    failure_reason VARCHAR(512),
    archive_token VARCHAR(36),
    archive_until TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, company_id, author_user_id, author_post_id, idempotency_key),
    CHECK (status <> 'READY' OR cover_asset_id IS NOT NULL),
    CHECK (status <> 'ARCHIVE_PENDING' OR (provider_cover_id IS NOT NULL AND provider_url IS NOT NULL))
);
COMMENT ON TABLE fr_content_cover_request IS '封面生成幂等事实；未知提交不得自动重投，已生成图片仅重试归档';
