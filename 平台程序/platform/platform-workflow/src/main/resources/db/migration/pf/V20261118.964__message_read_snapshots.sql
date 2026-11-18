CREATE TABLE pf_message_read_snapshot (
    user_id BIGINT NOT NULL,
    org_id BIGINT NOT NULL,
    snapshot_at TIMESTAMPTZ NOT NULL,
    message_ids BIGINT[] NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (clock_timestamp() + INTERVAL '30 minutes'),
    PRIMARY KEY (user_id, org_id, snapshot_at)
);
CREATE INDEX idx_pf_message_read_snapshot_expiry ON pf_message_read_snapshot(expires_at);
