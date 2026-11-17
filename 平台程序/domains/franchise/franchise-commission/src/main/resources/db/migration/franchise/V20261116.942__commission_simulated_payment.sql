CREATE TABLE fr_commission_simulated_order (
    commission_id BIGINT NOT NULL REFERENCES fr_commission(id),
    submission_version INTEGER NOT NULL CHECK(submission_version>0),
    company_id BIGINT NOT NULL,
    design_org_id BIGINT NOT NULL,
    order_number VARCHAR(80) NOT NULL UNIQUE,
    amount_cents BIGINT NOT NULL CHECK(amount_cents>0),
    status VARCHAR(16) NOT NULL CHECK(status IN('PENDING','PAID')),
    channel VARCHAR(16) CHECK(channel IN('WECHAT','ALIPAY')),
    paid_by_user_id BIGINT,
    paid_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(commission_id,submission_version),
    CHECK((status='PENDING' AND channel IS NULL AND paid_at IS NULL AND paid_by_user_id IS NULL)
       OR (status='PAID' AND channel IS NOT NULL AND paid_at IS NOT NULL AND paid_by_user_id IS NOT NULL))
);
COMMENT ON TABLE fr_commission_simulated_order IS 'Simulation only. Never actual receipts or payables.';
ALTER TABLE pf_message DROP CONSTRAINT ck_pf_message_route;
ALTER TABLE pf_message ADD CONSTRAINT ck_pf_message_route CHECK(route IN(
 'customer','course','personal-performance','privacy','design-dispatch','design-progress','commission','content-task','commission-payment'
));
