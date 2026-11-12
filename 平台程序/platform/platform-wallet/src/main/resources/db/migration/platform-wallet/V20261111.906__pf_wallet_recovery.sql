CREATE TABLE pf_wallet_recovery_task (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    wallet_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('PAYMENT','CREDIT')),
    status VARCHAR(16) NOT NULL DEFAULT 'READY' CHECK (status IN ('READY','RUNNING','DONE')),
    lease_token VARCHAR(64),
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, wallet_id, order_id, kind),
    FOREIGN KEY (tenant_id, wallet_id, order_id) REFERENCES pf_recharge_order(tenant_id, wallet_id, id)
);
CREATE INDEX idx_pf_wallet_recovery_due ON pf_wallet_recovery_task(next_attempt_at, id) WHERE status <> 'DONE';
ALTER TABLE pf_payment_notification_inbox ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX idx_pf_payment_inbox_due ON pf_payment_notification_inbox(next_attempt_at, id) WHERE status IN ('PENDING','UNKNOWN');
