-- V20261114.918 总部价格发布幂等事实；请求键绑定完整审批载荷和首次价格版本。
CREATE TABLE org_point_hq_price_request (
  id             BIGINT PRIMARY KEY,
  request_key    VARCHAR(128) NOT NULL UNIQUE,
  request_hash   VARCHAR(64)  NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
  tenant_id      BIGINT       NOT NULL CHECK (tenant_id > 0),
  actor_id       BIGINT       NOT NULL CHECK (actor_id > 0),
  subject        VARCHAR(64)  NOT NULL,
  price_micros   BIGINT       NOT NULL CHECK (price_micros > 0),
  min_charge     BIGINT       NOT NULL CHECK (min_charge > 0),
  reason         VARCHAR(512) NOT NULL CHECK (length(trim(reason)) > 0),
  price_id       BIGINT       NOT NULL UNIQUE,
  create_time    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE FUNCTION org_point_hq_price_request_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'org_point_hq_price_request is append-only';
END $$;
CREATE TRIGGER trg_ophpr_append_only BEFORE UPDATE OR DELETE ON org_point_hq_price_request
FOR EACH ROW EXECUTE FUNCTION org_point_hq_price_request_append_only();
