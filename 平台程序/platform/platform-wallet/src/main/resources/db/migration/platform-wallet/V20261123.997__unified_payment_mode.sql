-- Global payment execution mode. New environments start in MOCK mode.
CREATE TABLE pf_payment_runtime_mode (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    mode VARCHAR(8) NOT NULL CHECK (mode IN ('MOCK','REAL')),
    version BIGINT NOT NULL CHECK (version > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(64) NOT NULL,
    reason VARCHAR(512) NOT NULL CHECK (length(trim(reason)) > 0)
);
INSERT INTO pf_payment_runtime_mode(id, mode, version, updated_by, reason)
VALUES (1, 'MOCK', 1, 'system', '安全默认：真实支付双渠道尚未完成配置验证');

CREATE TABLE pf_payment_runtime_mode_audit (
    id BIGINT PRIMARY KEY,
    config_version BIGINT NOT NULL UNIQUE,
    old_mode VARCHAR(8) NOT NULL CHECK (old_mode IN ('MOCK','REAL')),
    new_mode VARCHAR(8) NOT NULL CHECK (new_mode IN ('MOCK','REAL')),
    actor_id BIGINT NOT NULL,
    reason VARCHAR(512) NOT NULL CHECK (length(trim(reason)) > 0),
    create_time TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX pf_payment_mode_audit_page ON pf_payment_runtime_mode_audit(create_time DESC, id DESC);

CREATE FUNCTION pf_payment_mode_audit_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'payment mode audit is append-only';
END;
$$;
CREATE TRIGGER pf_payment_mode_audit_append_only
BEFORE UPDATE OR DELETE ON pf_payment_runtime_mode_audit
FOR EACH ROW EXECUTE FUNCTION pf_payment_mode_audit_append_only();

INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater)
VALUES
(997000,'支付中心','',1,56,0,'/platform-payment','ep:wallet',NULL,NULL,0,TRUE,FALSE,TRUE,'1','1'),
(997001,'支付配置','platform:payment:mode:query',2,1,997000,'mode','ep:setting','platform/payment/index','PlatformPaymentMode',0,TRUE,FALSE,TRUE,'1','1'),
(997011,'切换支付模式','platform:payment:mode:update',3,1,997001,'','',NULL,NULL,0,TRUE,FALSE,TRUE,'1','1');
