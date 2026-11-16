CREATE TABLE fr_content_hifly_upload (
    id BIGINT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    persona_id BIGINT NOT NULL,
    author_user_id BIGINT NOT NULL,
    author_post_id BIGINT NOT NULL,
    kind VARCHAR(8) NOT NULL CHECK (kind IN ('AVATAR','VOICE')),
    provider_file_id VARCHAR(200) NOT NULL UNIQUE,
    mime_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    declared_sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ISSUED','UPLOADED')),
    expires_at TIMESTAMPTZ NOT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);
COMMENT ON TABLE fr_content_hifly_upload IS '飞影直传元数据；不接收或存储素材字节，UPLOADED仅表示客户端报告PUT成功';
CREATE INDEX idx_fr_hifly_upload_owner ON fr_content_hifly_upload(company_id,persona_id,author_user_id,id);
ALTER TABLE fr_content_digital_clone ADD COLUMN source_upload_id BIGINT REFERENCES fr_content_hifly_upload(id);
ALTER TABLE fr_content_digital_clone ALTER COLUMN source_asset_id DROP NOT NULL;
ALTER TABLE fr_content_digital_clone ADD CONSTRAINT ck_fr_clone_one_source
    CHECK (num_nonnulls(source_asset_id,source_upload_id)=1);
