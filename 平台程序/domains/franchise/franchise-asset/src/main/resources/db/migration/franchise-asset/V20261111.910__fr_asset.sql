-- B 专属迁移目录；融合 A 不扫描此目录。无 A account/asset 外键或跨库读取。
CREATE TABLE fr_asset (
    id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    org_id BIGINT NOT NULL,
    service_account_id BIGINT NOT NULL,
    creator_user_id BIGINT NOT NULL,
    creator_post_id BIGINT NOT NULL,
    asset_type VARCHAR(32) NOT NULL CHECK (asset_type IN ('FIN_VOUCHER','ENG_DRAWING','ENG_SITE_PHOTO','CONTRACT_ATTACHMENT','CONTENT_ASSET')),
    declared_mime VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    sha256 VARCHAR(64) NOT NULL,
    upload_key VARCHAR(512) NOT NULL UNIQUE,
    object_key VARCHAR(512),
    accepted_size_bytes BIGINT,
    accepted_sha256 VARCHAR(64),
    upload_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (upload_status IN ('PENDING','VALIDATING','ACCEPTED','REJECTED')),
    validation_token VARCHAR(64),
    validation_started_at TIMESTAMP WITH TIME ZONE,
    rejected_reason VARCHAR(1024),
    scan_result VARCHAR(2048),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (upload_status <> 'ACCEPTED' OR (object_key IS NOT NULL AND accepted_sha256 IS NOT NULL AND accepted_size_bytes > 0))
);
CREATE INDEX idx_fr_asset_owner ON fr_asset (tenant_id, org_id, id);

CREATE TABLE fr_asset_upload_ticket (
    token_hash VARCHAR(64) PRIMARY KEY,
    asset_id BIGINT NOT NULL UNIQUE REFERENCES fr_asset(id),
    upload_mode VARCHAR(8) NOT NULL CHECK (upload_mode IN ('DIRECT','PROXY')),
    completed_at TIMESTAMP WITH TIME ZONE,
    tenant_id BIGINT NOT NULL,
    org_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE
);
CREATE TABLE fr_asset_download_ticket (
    token_hash VARCHAR(64) PRIMARY KEY,
    asset_id BIGINT NOT NULL REFERENCES fr_asset(id),
    tenant_id BIGINT NOT NULL,
    org_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_fr_asset_download_expiry ON fr_asset_download_ticket (expires_at);
