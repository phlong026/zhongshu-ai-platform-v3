CREATE TABLE design_provider_prompt_event_receipt (
    id UUID PRIMARY KEY,
    job_id BIGINT NOT NULL,
    attempt_no INTEGER NOT NULL CHECK (attempt_no > 0),
    fencing_token BIGINT NOT NULL CHECK (fencing_token > 0),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('DISPATCHED', 'SUCCEEDED', 'UNKNOWN', 'NOT_SENT')),
    call_id VARCHAR(160) NOT NULL,
    call_sha256 VARCHAR(64) NOT NULL CHECK (call_sha256 ~ '^[a-f0-9]{64}$'),
    event_sha256 VARCHAR(64) NOT NULL CHECK (event_sha256 ~ '^[a-f0-9]{64}$'),
    state VARCHAR(16) NOT NULL CHECK (state IN ('RECEIVING', 'APPLIED')),
    applied_at TIMESTAMPTZ,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_design_provider_prompt_event UNIQUE (job_id, outcome),
    CONSTRAINT ck_design_provider_prompt_event_applied CHECK (
        (state = 'RECEIVING' AND applied_at IS NULL)
        OR (state = 'APPLIED' AND applied_at IS NOT NULL)
    )
);
