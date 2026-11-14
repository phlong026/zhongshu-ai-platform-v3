-- B only. No migration is applied to the shared design application.
CREATE TABLE fr_design_reference_mirror (
    caller_app_id varchar(80) NOT NULL,
    tenant_id bigint NOT NULL CHECK (tenant_id > 0),
    org_id bigint NOT NULL CHECK (org_id > 0),
    user_id bigint NOT NULL CHECK (user_id > 0),
    post_id bigint NOT NULL CHECK (post_id > 0),
    source_asset_id bigint NOT NULL CHECK (source_asset_id > 0),
    source_sha256 char(64) NOT NULL CHECK (source_sha256 ~ '^[a-f0-9]{64}$'),
    attempt_key varchar(36) NOT NULL,
    attempt_started boolean NOT NULL DEFAULT FALSE,
    accepted_asset_id bigint CHECK (accepted_asset_id > 0),
    lease_token varchar(36),
    lease_until timestamptz,
    PRIMARY KEY (caller_app_id,tenant_id,org_id,user_id,post_id,source_asset_id,source_sha256)
);
