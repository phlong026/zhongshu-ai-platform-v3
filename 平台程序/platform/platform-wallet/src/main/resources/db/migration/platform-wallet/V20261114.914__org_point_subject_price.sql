-- V20261114.914 B独立公司池科目价格表：后台可调价，预扣仍写快照
CREATE TABLE org_point_subject_price (
  id           BIGINT PRIMARY KEY,
  subject      VARCHAR(64) NOT NULL,
  unit         VARCHAR(16) NOT NULL,
  price_micros BIGINT      NOT NULL CHECK (price_micros > 0),
  min_charge   BIGINT      NOT NULL DEFAULT 1
                 CHECK (min_charge >= 1 AND min_charge <= 1000000000),
  version      INT         NOT NULL,
  status       VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  effective_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  retired_at   TIMESTAMPTZ,
  operator     VARCHAR(64),
  retired_by   VARCHAR(64),
  create_time  TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_ops_price_unit CHECK (unit IN ('CALL','SECOND')),
  CONSTRAINT ck_ops_price_status CHECK (status IN ('ACTIVE','RETIRED'))
);
-- 每科目至多一条 ACTIVE 价
CREATE UNIQUE INDEX uk_ops_price_active ON org_point_subject_price (subject) WHERE status = 'ACTIVE';
-- 版本号科目内唯一：并发发布即使串行化失效也只会撞唯一键，绝不出现两个 db-vN
ALTER TABLE org_point_subject_price ADD CONSTRAINT uk_ops_price_version UNIQUE (subject, version);
CREATE INDEX idx_ops_price_subject ON org_point_subject_price (subject, version DESC);
