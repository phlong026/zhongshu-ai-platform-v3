CREATE TABLE fr_content_material (
    asset_id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    author_user_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    visibility VARCHAR(16) NOT NULL CHECK (visibility IN ('PERSONAL','COMPANY')),
    mime_type VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 52428800),
    duration_sec INTEGER NOT NULL DEFAULT 0 CHECK (duration_sec BETWEEN 0 AND 86400),
    parent_asset_id BIGINT REFERENCES fr_content_material(asset_id),
    state VARCHAR(16) NOT NULL DEFAULT 'UPLOADING' CHECK (state IN ('UPLOADING','READY')),
    removed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_content_material_personal ON fr_content_material(tenant_id,company_id,author_user_id,created_at DESC,asset_id DESC) WHERE removed=FALSE AND parent_asset_id IS NULL;
CREATE INDEX idx_content_material_company ON fr_content_material(tenant_id,company_id,visibility,created_at DESC,asset_id DESC) WHERE removed=FALSE AND parent_asset_id IS NULL;
CREATE INDEX idx_content_material_cover ON fr_content_material(parent_asset_id) WHERE parent_asset_id IS NOT NULL;
