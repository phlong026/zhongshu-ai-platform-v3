-- B 独立钱包。余额、账本、charge 在同一个 PostgreSQL 本地事务内变更。
CREATE TABLE pf_wallet (
    id BIGINT PRIMARY KEY,
    org_id BIGINT NOT NULL UNIQUE REFERENCES pf_org_unit(dept_id),
    tenant_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED', 'CLOSED')),
    spending_frozen BOOLEAN NOT NULL DEFAULT FALSE,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, id, org_id)
);
CREATE TABLE pf_point_account (
    wallet_id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    available_points BIGINT NOT NULL DEFAULT 0 CHECK (available_points >= 0),
    reserved_points BIGINT NOT NULL DEFAULT 0 CHECK (reserved_points >= 0),
    CHECK (available_points::numeric + reserved_points::numeric <= 9223372036854775807),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, wallet_id) REFERENCES pf_wallet(tenant_id, id)
);
CREATE TABLE pf_point_ledger (
    id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    wallet_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    type VARCHAR(40) NOT NULL,
    delta BIGINT NOT NULL CHECK (delta <> 0),
    available_after BIGINT NOT NULL CHECK (available_after >= 0),
    reserved_after BIGINT NOT NULL CHECK (reserved_after >= 0),
    biz_type VARCHAR(64) NOT NULL,
    biz_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL CHECK (length(idempotency_key) > 0),
    request_hash CHAR(64) NOT NULL,
    reason VARCHAR(512) NOT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, idempotency_key),
    UNIQUE (tenant_id, wallet_id, id),
    FOREIGN KEY (tenant_id, wallet_id) REFERENCES pf_wallet(tenant_id, id)
);
CREATE INDEX idx_pf_point_ledger_page ON pf_point_ledger(tenant_id, wallet_id, create_time DESC, id DESC);
CREATE FUNCTION pf_wallet_ledger_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'pf_point_ledger is append-only';
END;
$$;
CREATE TRIGGER trg_pf_point_ledger_append_only BEFORE UPDATE OR DELETE ON pf_point_ledger
FOR EACH ROW EXECUTE FUNCTION pf_wallet_ledger_append_only();

CREATE TABLE pf_wallet_charge (
    id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    wallet_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    biz_type VARCHAR(64) NOT NULL,
    biz_id VARCHAR(128) NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    idempotency_key VARCHAR(128) NOT NULL CHECK (length(idempotency_key) > 0),
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RESERVED', 'CONSUMED', 'RELEASED', 'SETTLED')),
    charged_points BIGINT NOT NULL DEFAULT 0 CHECK (charged_points >= 0 AND charged_points <= amount),
    settlement_version BIGINT NOT NULL DEFAULT -1 CHECK (settlement_version >= -1),
    terminal_operation VARCHAR(24) CHECK (terminal_operation IN ('CONSUME', 'RELEASE', 'TASK_SETTLEMENT')),
    CHECK ((status = 'RESERVED') = (terminal_operation IS NULL)),
    ledger_id BIGINT REFERENCES pf_point_ledger(id),
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, idempotency_key),
    UNIQUE (tenant_id, wallet_id, biz_type, biz_id),
    FOREIGN KEY (tenant_id, wallet_id) REFERENCES pf_wallet(tenant_id, id),
    CHECK ((charged_points > 0) = (ledger_id IS NOT NULL)),
    CHECK (status <> 'CONSUMED' OR charged_points = amount),
    CHECK (status <> 'RELEASED' OR charged_points = 0)
);
