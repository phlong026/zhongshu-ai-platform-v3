CREATE TABLE pf_refund_order (
    id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    wallet_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    refund_no VARCHAR(128) NOT NULL,
    origin VARCHAR(16) NOT NULL CHECK (origin IN ('MERCHANT', 'EXTERNAL')),
    actor_id BIGINT NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    amount_fen BIGINT NOT NULL CHECK (amount_fen > 0),
    base_points BIGINT NOT NULL CHECK (base_points > 0),
    bonus_points BIGINT NOT NULL CHECK (bonus_points >= 0),
    reason INTEGER NOT NULL CHECK (reason BETWEEN 0 AND 5),
    channel VARCHAR(32) NOT NULL,
    appid VARCHAR(64) NOT NULL,
    offer_id VARCHAR(128) NOT NULL,
    environment INTEGER NOT NULL CHECK (environment IN (0, 1)),
    channel_refund_id VARCHAR(128),
    channel_state VARCHAR(16) NOT NULL CHECK (channel_state IN ('UNKNOWN', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    point_state VARCHAR(16) NOT NULL CHECK (point_state IN ('RESERVED', 'REVERSED', 'RELEASED', 'BLOCKED', 'DEBT')),
    order_type INTEGER,
    order_status INTEGER,
    lease_token VARCHAR(64),
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, wallet_id, refund_no),
    UNIQUE (tenant_id, request_key),
    UNIQUE (channel, appid, offer_id, environment, channel_refund_id),
    FOREIGN KEY (tenant_id, wallet_id, order_id) REFERENCES pf_recharge_order(tenant_id, wallet_id, id),
    CHECK (channel_refund_id IS NULL OR length(channel_refund_id) > 0),
    CHECK (channel_state <> 'SUCCEEDED' OR (order_type IS NOT NULL AND order_status IS NOT NULL AND order_type IN (1, 8) AND order_status = 8 AND channel_refund_id IS NOT NULL)),
    CHECK (channel_state <> 'FAILED' OR (order_type IS NOT NULL AND order_status IS NOT NULL AND order_type IN (1, 8) AND order_status = 7 AND channel_refund_id IS NOT NULL))
);
-- 外部退款可与在途商户退款并存，必须保留事实；只允许一个未失败的主动退款。
CREATE UNIQUE INDEX uq_pf_refund_merchant_live ON pf_refund_order(tenant_id, wallet_id, order_id)
    WHERE origin = 'MERCHANT' AND channel_state <> 'FAILED';
CREATE INDEX idx_pf_refund_recovery ON pf_refund_order(next_attempt_at, id)
    WHERE channel_state IN ('UNKNOWN', 'PROCESSING') OR (channel_state IN ('SUCCEEDED', 'FAILED') AND point_state = 'RESERVED');
