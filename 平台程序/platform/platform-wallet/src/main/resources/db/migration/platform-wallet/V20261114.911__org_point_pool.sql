-- V20261114.911 B独立公司积分池：账户 + 三态流水 + 员工月度额度 + 业务权限矩阵
-- 口径：可用余额=SUM(available_delta)；在途预留=未终结 operation 汇总；流水只追加永不物理删除。
CREATE TABLE org_point_account (
  id           BIGINT PRIMARY KEY,
  org_id       BIGINT      NOT NULL UNIQUE,
  balance      BIGINT      NOT NULL DEFAULT 0 CHECK (balance >= 0),
  status       TEXT        NOT NULL DEFAULT 'ACTIVE',
  version      BIGINT      NOT NULL DEFAULT 0,
  create_time  TIMESTAMPTZ NOT NULL DEFAULT now(),
  update_time  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE org_point_ledger (
  id                  BIGINT PRIMARY KEY,
  org_id              BIGINT      NOT NULL,
  operation_id        BIGINT,
  biz_key             TEXT        NOT NULL UNIQUE,
  operation_time      TIMESTAMPTZ,
  type                TEXT        NOT NULL,
  subject             TEXT,
  actor_user_id       BIGINT,
  actor_post_id       BIGINT,
  available_delta     BIGINT      NOT NULL,
  billing_month       TEXT,
  price_snapshot      JSONB,
  ref_type            TEXT,
  ref_id              BIGINT,
  note                TEXT,
  create_time         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_opl_org_time   ON org_point_ledger (org_id, create_time);
CREATE INDEX idx_opl_actor      ON org_point_ledger (org_id, actor_user_id, billing_month);
CREATE UNIQUE INDEX uk_opl_reserve  ON org_point_ledger (operation_id) WHERE type = 'RESERVE' AND operation_id IS NOT NULL;
CREATE UNIQUE INDEX uk_opl_terminal ON org_point_ledger (operation_id) WHERE type IN ('SETTLE','RELEASE') AND operation_id IS NOT NULL;

CREATE FUNCTION org_point_ledger_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'org_point_ledger is append-only';
END $$;
CREATE TRIGGER trg_org_point_ledger_append_only BEFORE UPDATE OR DELETE ON org_point_ledger
FOR EACH ROW EXECUTE FUNCTION org_point_ledger_append_only();

CREATE TABLE org_member_quota (
  id               BIGINT PRIMARY KEY,
  org_id           BIGINT      NOT NULL,
  user_id          BIGINT      NOT NULL,
  monthly_credits  BIGINT      NOT NULL CHECK (monthly_credits > 0 AND monthly_credits <= 1000000000),
  create_time      TIMESTAMPTZ NOT NULL DEFAULT now(),
  update_time      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (org_id, user_id)
);
CREATE TABLE org_member_permission (
  id                BIGINT PRIMARY KEY,
  org_id            BIGINT      NOT NULL,
  user_id           BIGINT      NOT NULL,
  allowed_subjects  TEXT        NOT NULL,
  create_time       TIMESTAMPTZ NOT NULL DEFAULT now(),
  update_time       TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (org_id, user_id)
);
-- End of company point pool schema.
