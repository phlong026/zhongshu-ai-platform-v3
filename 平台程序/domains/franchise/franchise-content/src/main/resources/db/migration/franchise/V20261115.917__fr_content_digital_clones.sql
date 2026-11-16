CREATE TABLE fr_content_digital_clone (
    id BIGINT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    persona_id BIGINT NOT NULL,
    author_user_id BIGINT NOT NULL,
    author_post_id BIGINT NOT NULL,
    kind VARCHAR(8) NOT NULL CHECK (kind IN ('AVATAR','VOICE')),
    title VARCHAR(20) NOT NULL,
    source_asset_id BIGINT NOT NULL,
    source_sha256 VARCHAR(64) NOT NULL,
    consent_version VARCHAR(40) NOT NULL,
    consented_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('SUBMITTING','RUNNING','READY','FAILED','UNKNOWN')),
    provider_task_id VARCHAR(200),
    provider_resource_id VARCHAR(200),
    demo_url TEXT,
    failure_reason VARCHAR(500),
    operation_id BIGINT NOT NULL,
    billing_state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (billing_state IN ('PENDING','SETTLED','RELEASED')),
    refresh_until TIMESTAMPTZ,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(company_id,author_user_id,idempotency_key),
    CHECK (status <> 'READY' OR provider_resource_id IS NOT NULL)
);
CREATE INDEX idx_fr_digital_clone_persona ON fr_content_digital_clone(company_id,persona_id,id DESC);
CREATE INDEX idx_fr_digital_clone_billing ON fr_content_digital_clone(billing_state,status);
