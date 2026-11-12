-- B only. Retail points are configured independently of A internal MICRO_CNY costs.
CREATE TABLE fr_design_price (
    tenant_id BIGINT NOT NULL,
    id BIGINT NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    operation VARCHAR(64) NOT NULL,
    resolution VARCHAR(8) NOT NULL DEFAULT '',
    unit VARCHAR(24) NOT NULL CHECK (unit='B_WALLET_POINTS'),
    prompt_points BIGINT NOT NULL CHECK (prompt_points >= 0),
    image_points BIGINT NOT NULL CHECK (image_points >= 0),
    usage_points BIGINT NOT NULL CHECK (usage_points >= 0),
    max_prompt_units INT NOT NULL CHECK (max_prompt_units BETWEEN 0 AND 1),
    max_image_units INT NOT NULL CHECK (max_image_units BETWEEN 0 AND 8),
    max_usage_units INT NOT NULL CHECK (max_usage_units BETWEEN 0 AND 1),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(tenant_id,id,version)
);
CREATE UNIQUE INDEX fr_design_active_price ON fr_design_price(tenant_id,operation,resolution) WHERE active;

CREATE TABLE fr_design_billing (
    command_id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    org_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    actor_post_id BIGINT NOT NULL,
    operation VARCHAR(64) NOT NULL,
    price_id VARCHAR(120) NOT NULL,
    price_version BIGINT NOT NULL,
    prompt_points BIGINT NOT NULL CHECK (prompt_points >= 0),
    image_points BIGINT NOT NULL CHECK (image_points >= 0),
    usage_points BIGINT NOT NULL CHECK (usage_points >= 0),
    max_prompt_units INT NOT NULL,
    max_image_units INT NOT NULL,
    max_usage_units INT NOT NULL,
    reserved_points BIGINT NOT NULL CHECK (reserved_points >= 0),
    charged_points BIGINT NOT NULL DEFAULT 0 CHECK (charged_points >= 0),
    refunded_points BIGINT NOT NULL DEFAULT 0 CHECK (refunded_points >= 0),
    charge_id VARCHAR(64),
    settlement_version BIGINT NOT NULL DEFAULT 0 CHECK (settlement_version >= 0),
    settlement_fingerprint TEXT,
    prompt_units BIGINT NOT NULL DEFAULT 0,
    image_units BIGINT NOT NULL DEFAULT 0,
    usage_units BIGINT NOT NULL DEFAULT 0,
    terminal BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (charged_points + refunded_points <= reserved_points),
    CHECK (prompt_units BETWEEN 0 AND max_prompt_units),
    CHECK (image_units BETWEEN 0 AND max_image_units),
    CHECK (usage_units BETWEEN 0 AND max_usage_units)
);
-- Intentionally no price inserts. No real rate means the remote design provider cannot be enabled.
