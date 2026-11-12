-- 总部运营权限独立于组织岗位钱包权限。仅注册，不向任何角色或员工自动授权。
INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater)
VALUES
(960000,'总部运营','',1,55,0,'/platform-ops','ep:office-building',NULL,NULL,0,TRUE,FALSE,TRUE,'1','1'),
(960001,'组织钱包','platform:wallet:query',2,1,960000,'wallets','ep:wallet','platform/wallet/index','PlatformOpsWallets',0,TRUE,FALSE,TRUE,'1','1'),
(960002,'充值套餐','platform:wallet:plan:query',2,2,960000,'plans','ep:tickets','platform/wallet/index','PlatformOpsPlans',0,TRUE,FALSE,TRUE,'1','1'),
(960003,'充值订单','platform:wallet:order:query',2,3,960000,'orders','ep:document','platform/wallet/index','PlatformOpsOrders',0,TRUE,FALSE,TRUE,'1','1'),
(960004,'积分流水','platform:wallet:ledger:query',2,4,960000,'ledger','ep:coin','platform/wallet/index','PlatformOpsLedger',0,TRUE,FALSE,TRUE,'1','1'),
(960005,'支付异常核对','platform:wallet:anomaly:query',2,5,960000,'anomalies','ep:warning','platform/wallet/index','PlatformOpsAnomalies',0,TRUE,FALSE,TRUE,'1','1'),
(960006,'配置审计','platform:ops:audit:query',2,8,960000,'audit','ep:document-checked','platform/wallet/index','PlatformOpsAudit',0,TRUE,FALSE,TRUE,'1','1'),
(960011,'维护充值套餐','platform:wallet:plan:update',3,1,960002,'','',NULL,NULL,0,TRUE,FALSE,TRUE,'1','1'),
(960012,'记录异常核对','platform:wallet:anomaly:review',3,1,960005,'','',NULL,NULL,0,TRUE,FALSE,TRUE,'1','1');

CREATE TABLE pf_ops_config_audit (
    id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    resource_version BIGINT NOT NULL CHECK(resource_version > 0),
    action VARCHAR(32) NOT NULL,
    actor_id BIGINT NOT NULL,
    reason VARCHAR(512) NOT NULL CHECK(length(trim(reason)) > 0),
    before_json JSONB NOT NULL,
    after_json JSONB NOT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id,resource_type,resource_id,resource_version)
);
CREATE INDEX pf_ops_audit_page ON pf_ops_config_audit(tenant_id,create_time DESC,id DESC);
CREATE FUNCTION pf_ops_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'platform operations audit is append-only';
END;
$$;
CREATE TRIGGER pf_ops_config_audit_append_only BEFORE UPDATE OR DELETE ON pf_ops_config_audit
FOR EACH ROW EXECUTE FUNCTION pf_ops_append_only();

CREATE UNIQUE INDEX pf_ops_anomaly_tenant_id ON pf_payment_anomaly_audit(tenant_id,id);
CREATE TABLE pf_ops_anomaly_review (
    id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    anomaly_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    note VARCHAR(512) NOT NULL CHECK(length(trim(note)) > 0),
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY(tenant_id,anomaly_id) REFERENCES pf_payment_anomaly_audit(tenant_id,id)
);
CREATE INDEX pf_ops_anomaly_review_page ON pf_ops_anomaly_review(tenant_id,anomaly_id,create_time DESC,id DESC);
CREATE TRIGGER pf_ops_anomaly_review_append_only BEFORE UPDATE OR DELETE ON pf_ops_anomaly_review
FOR EACH ROW EXECUTE FUNCTION pf_ops_append_only();
CREATE INDEX pf_ops_wallet_page ON pf_wallet(tenant_id,id DESC);
CREATE INDEX pf_ops_order_page ON pf_recharge_order(tenant_id,create_time DESC,id DESC);
CREATE INDEX pf_ops_ledger_page ON pf_point_ledger(tenant_id,create_time DESC,id DESC);
CREATE INDEX pf_ops_anomaly_page ON pf_payment_anomaly_audit(tenant_id,create_time DESC,id DESC);
