-- V20261113.110 旧设计账号 → 员工 → 组织 身份映射（24 号任务书 B-15）
-- 用途：激活码退役与存量点数迁移（B-14/B-16）前，先落"候选映射 + 人工确认 + 未匹配过渡清单"。
CREATE TABLE design_identity_mapping (
  id                 BIGINT PRIMARY KEY,
  account_id         BIGINT       NOT NULL UNIQUE,
  contact_phone      VARCHAR(30),
  batch_id           BIGINT,
  batch_issued_by    VARCHAR(64),
  batch_purpose_note VARCHAR(512),
  matched_user_id    BIGINT,
  matched_org_id     BIGINT,
  match_type         VARCHAR(16)  NOT NULL,
  status             VARCHAR(16)  NOT NULL,
  note               VARCHAR(512),
  operator           VARCHAR(64),
  create_time        TIMESTAMPTZ  NOT NULL DEFAULT now(),
  update_time        TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT ck_dim_match_type CHECK (match_type IN ('PHONE','MANUAL','NONE')),
  CONSTRAINT ck_dim_status CHECK (status IN ('CANDIDATE','CONFIRMED','UNMATCHED','REJECTED'))
);
CREATE INDEX idx_dim_status ON design_identity_mapping (status);
CREATE INDEX idx_dim_org ON design_identity_mapping (matched_org_id);
