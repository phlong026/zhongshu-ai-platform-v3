ALTER TABLE pf_recharge_order ADD COLUMN credit_target varchar(16) NOT NULL DEFAULT 'WALLET'
  CHECK (credit_target IN ('WALLET','COMPANY'));
CREATE INDEX pf_company_recharge_current ON pf_recharge_order(tenant_id,org_id,actor_id,post_id,create_time DESC,id DESC)
  WHERE credit_target='COMPANY';
