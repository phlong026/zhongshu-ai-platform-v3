CREATE TABLE design_provider_output_ticket (
    id UUID PRIMARY KEY,
    job_id BIGINT NOT NULL,
    attempt_no INTEGER NOT NULL CHECK (attempt_no > 0),
    fencing_token BIGINT NOT NULL CHECK (fencing_token > 0),
    candidate_slot_no INTEGER NOT NULL CHECK (candidate_slot_no BETWEEN 1 AND 4),
    object_key VARCHAR(300) NOT NULL UNIQUE,
    mime_type VARCHAR(32) NOT NULL CHECK (mime_type IN ('image/png', 'image/jpeg')),
    state VARCHAR(16) NOT NULL DEFAULT 'ISSUED' CHECK (state IN ('ISSUED', 'CONSUMED')),
    source_event_id VARCHAR(128),
    content_sha256 VARCHAR(64),
    size_bytes BIGINT,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_design_provider_output_ticket_lease_slot
        UNIQUE (job_id, attempt_no, fencing_token, candidate_slot_no),
    CONSTRAINT ck_design_provider_output_ticket_consumption CHECK (
        (state = 'ISSUED' AND source_event_id IS NULL AND content_sha256 IS NULL
            AND size_bytes IS NULL AND consumed_at IS NULL)
        OR
        (state = 'CONSUMED' AND source_event_id IS NOT NULL AND content_sha256 IS NOT NULL
            AND size_bytes IS NOT NULL AND size_bytes >= 0 AND consumed_at IS NOT NULL)
    )
);

CREATE INDEX idx_design_provider_output_ticket_job
    ON design_provider_output_ticket (job_id, attempt_no, fencing_token);
