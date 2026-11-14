-- V20261114.912 B独立设计计费归属绑定：创建时持久化来源，异步/结算/恢复按此行判定路由
CREATE TABLE design_billing_binding (
  id            BIGINT PRIMARY KEY,
  origin        TEXT        NOT NULL,
  org_id        BIGINT      NOT NULL,
  actor_user_id BIGINT      NOT NULL,
  actor_post_id BIGINT,
  ref_type      TEXT        NOT NULL,
  ref_id        BIGINT      NOT NULL,
  subject       TEXT        NOT NULL,
  operation_id  BIGINT      NOT NULL,
  biz_key       TEXT        NOT NULL,
  create_time   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (ref_type, ref_id, subject)
);
CREATE INDEX idx_dbb_org_time ON design_billing_binding (org_id, create_time);
