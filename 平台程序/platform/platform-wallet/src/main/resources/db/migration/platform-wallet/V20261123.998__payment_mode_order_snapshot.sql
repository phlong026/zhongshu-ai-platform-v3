ALTER TABLE pf_recharge_order
  ADD COLUMN payment_mode VARCHAR(8) NOT NULL DEFAULT 'REAL'
    CHECK (payment_mode IN ('MOCK','REAL')),
  ADD COLUMN payment_mode_version BIGINT NOT NULL DEFAULT 1
    CHECK (payment_mode_version > 0);

ALTER TABLE pf_payment_transaction
  ADD COLUMN payment_mode VARCHAR(8) NOT NULL DEFAULT 'REAL'
    CHECK (payment_mode IN ('MOCK','REAL'));

CREATE INDEX pf_recharge_mode_page
  ON pf_recharge_order(tenant_id, payment_mode, create_time DESC, id DESC);
CREATE INDEX pf_payment_transaction_mode_page
  ON pf_payment_transaction(tenant_id, payment_mode, paid_at DESC, id DESC);
