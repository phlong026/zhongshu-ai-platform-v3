-- V20261114.917 B 公司积分池总部管理：权限只注册不自动授权；调点必须双人复核。
INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater)
VALUES
(917000,'查询公司积分池','platform:org-point:query',3,9,960000,'','',NULL,NULL,0,FALSE,FALSE,FALSE,'1','1'),
(917001,'提交公司积分调点','platform:org-point:adjustment:submit',3,10,960000,'','',NULL,NULL,0,FALSE,FALSE,FALSE,'1','1'),
(917002,'复核公司积分调点','platform:org-point:adjustment:review',3,11,960000,'','',NULL,NULL,0,FALSE,FALSE,FALSE,'1','1'),
(917003,'查看公司积分价格','platform:org-point:price:query',3,12,960000,'','',NULL,NULL,0,FALSE,FALSE,FALSE,'1','1'),
(917004,'维护公司积分价格','platform:org-point:price:update',3,13,960000,'','',NULL,NULL,0,FALSE,FALSE,FALSE,'1','1');

CREATE TABLE org_point_hq_adjustment (
  id               BIGINT PRIMARY KEY,
  org_id           BIGINT      NOT NULL,
  delta            BIGINT      NOT NULL CHECK (delta <> 0),
  reason           VARCHAR(512) NOT NULL CHECK (length(trim(reason)) > 0),
  request_key      VARCHAR(128) NOT NULL UNIQUE,
  request_hash     VARCHAR(64)  NOT NULL,
  status           VARCHAR(16)  NOT NULL DEFAULT 'SUBMITTED'
                     CHECK (status IN ('SUBMITTED','EXECUTED','REJECTED')),
  maker_id         BIGINT      NOT NULL,
  checker_id       BIGINT,
  checker_comment  VARCHAR(512),
  result_balance   BIGINT,
  create_time      TIMESTAMPTZ NOT NULL DEFAULT now(),
  update_time      TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK ((status = 'SUBMITTED') = (checker_id IS NULL)),
  CHECK (status = 'SUBMITTED' OR checker_comment IS NOT NULL)
);
CREATE INDEX idx_opha_org_time ON org_point_hq_adjustment(org_id, create_time DESC, id DESC);

CREATE FUNCTION org_point_hq_adjustment_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status <> 'SUBMITTED' OR NEW.id <> OLD.id OR NEW.org_id <> OLD.org_id
     OR NEW.delta <> OLD.delta OR NEW.reason <> OLD.reason
     OR NEW.request_key <> OLD.request_key OR NEW.request_hash <> OLD.request_hash
     OR NEW.maker_id <> OLD.maker_id OR NEW.create_time <> OLD.create_time THEN
    RAISE EXCEPTION 'org_point_hq_adjustment immutable facts cannot change';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_opha_immutable BEFORE UPDATE ON org_point_hq_adjustment
FOR EACH ROW EXECUTE FUNCTION org_point_hq_adjustment_immutable();

CREATE TABLE org_point_price_audit (
  id             BIGINT PRIMARY KEY,
  subject        VARCHAR(64)  NOT NULL,
  price_id       BIGINT       NOT NULL,
  price_micros   BIGINT       NOT NULL,
  min_charge     BIGINT       NOT NULL,
  actor_id       BIGINT       NOT NULL,
  reason         VARCHAR(512) NOT NULL CHECK (length(trim(reason)) > 0),
  create_time    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE FUNCTION org_point_price_audit_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'org_point_price_audit is append-only';
END $$;
CREATE TRIGGER trg_oppa_append_only BEFORE UPDATE OR DELETE ON org_point_price_audit
FOR EACH ROW EXECUTE FUNCTION org_point_price_audit_append_only();
