-- A only. Internal measurement/quoted cost, not consumer points or an inter-application payment.
-- No default commercial rate: explicit reviewed configuration is required before enabling the provider.
CREATE TABLE platform_cost_price (
    product VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    unit VARCHAR(24) NOT NULL CHECK (unit = 'MICRO_CNY'),
    prompt_micros BIGINT NOT NULL CHECK (prompt_micros >= 0),
    image_micros BIGINT NOT NULL CHECK (image_micros >= 0),
    usage_micros BIGINT NOT NULL CHECK (usage_micros >= 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (product, version)
);
CREATE UNIQUE INDEX platform_cost_price_active ON platform_cost_price(product) WHERE active;

CREATE TABLE platform_cost_business (
    id VARCHAR(128) PRIMARY KEY,
    caller_app_id VARCHAR(80) NOT NULL,
    org_id BIGINT NOT NULL CHECK (org_id > 0),
    tenant_id BIGINT NOT NULL,
    service_account_id BIGINT NOT NULL CHECK (service_account_id > 0),
    request_key VARCHAR(200) NOT NULL,
    task_key VARCHAR(128) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    billing_mode VARCHAR(24) NOT NULL DEFAULT 'SERVICE_COST' CHECK (billing_mode = 'SERVICE_COST'),
    product VARCHAR(64) NOT NULL,
    price_version BIGINT NOT NULL,
    unit VARCHAR(24) NOT NULL CHECK (unit = 'MICRO_CNY'),
    prompt_micros BIGINT NOT NULL CHECK (prompt_micros >= 0),
    image_micros BIGINT NOT NULL CHECK (image_micros >= 0),
    usage_micros BIGINT NOT NULL CHECK (usage_micros >= 0),
    requested_count INT NOT NULL CHECK (requested_count BETWEEN 0 AND 8),
    job_id BIGINT UNIQUE,
    phase VARCHAR(16),
    project_ref VARCHAR(64),
    resolution VARCHAR(8),
    state VARCHAR(24) NOT NULL DEFAULT 'PREPARED',
    result_json TEXT,
    failure_evidence TEXT,
    job_status VARCHAR(24) NOT NULL DEFAULT 'PREPARED',
    images_final BOOLEAN NOT NULL DEFAULT FALSE,
    prompt_state VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (prompt_state IN ('PENDING','RESERVED','UNKNOWN','CHARGED','RELEASED','EXEMPT')),
    prompt_token BIGINT,
    prompt_expires_at TIMESTAMPTZ,
    prompt_call_id VARCHAR(128),
    prompt_response_hash VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    reserved_micros BIGINT NOT NULL CHECK (reserved_micros >= 0),
    charged_micros BIGINT NOT NULL DEFAULT 0 CHECK (charged_micros >= 0),
    refunded_micros BIGINT NOT NULL DEFAULT 0 CHECK (refunded_micros >= 0),
    prompt_units BIGINT NOT NULL DEFAULT 0 CHECK (prompt_units BETWEEN 0 AND 1),
    image_units BIGINT NOT NULL DEFAULT 0 CHECK (image_units >= 0),
    usage_units BIGINT NOT NULL DEFAULT 0 CHECK (usage_units BETWEEN 0 AND 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Receipt identity already includes caller/org/operation/request_key; product is not an operation key.
    UNIQUE (service_account_id, task_key),
    CHECK (charged_micros + refunded_micros <= reserved_micros),
    CHECK (image_units <= requested_count)
);

CREATE TABLE platform_cost_ledger (
    id BIGINT PRIMARY KEY,
    business_id VARCHAR(128) NOT NULL REFERENCES platform_cost_business(id),
    version BIGINT NOT NULL CHECK (version > 0),
    event_key VARCHAR(160) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    reserved_micros BIGINT NOT NULL CHECK (reserved_micros >= 0),
    charged_micros BIGINT NOT NULL CHECK (charged_micros >= 0),
    refunded_micros BIGINT NOT NULL CHECK (refunded_micros >= 0),
    prompt_units BIGINT NOT NULL CHECK (prompt_units >= 0),
    image_units BIGINT NOT NULL CHECK (image_units >= 0),
    usage_units BIGINT NOT NULL CHECK (usage_units >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (business_id, event_key),
    UNIQUE (business_id, version),
    CHECK (charged_micros + refunded_micros <= reserved_micros)
);

CREATE FUNCTION platform_cost_ledger_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'platform_cost_ledger is append-only';
END;
$$;
CREATE TRIGGER platform_cost_ledger_no_mutation BEFORE UPDATE OR DELETE ON platform_cost_ledger
FOR EACH ROW EXECUTE FUNCTION platform_cost_ledger_append_only();

ALTER TABLE ai_job ADD COLUMN billing_mode VARCHAR(24) NOT NULL DEFAULT 'CONSUMER_POINTS';
ALTER TABLE ai_job ADD COLUMN cost_business_id VARCHAR(128) REFERENCES platform_cost_business(id);
ALTER TABLE ai_job ADD CONSTRAINT ai_job_billing_subject CHECK (
    (billing_mode = 'CONSUMER_POINTS' AND cost_business_id IS NULL) OR
    (billing_mode = 'SERVICE_COST' AND cost_business_id IS NOT NULL)
);
