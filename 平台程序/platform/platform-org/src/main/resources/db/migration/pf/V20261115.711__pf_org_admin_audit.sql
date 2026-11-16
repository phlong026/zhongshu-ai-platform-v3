CREATE TABLE pf_org_admin_audit (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    org_id BIGINT NOT NULL REFERENCES pf_org_unit(dept_id),
    actor_id BIGINT NOT NULL,
    action VARCHAR(20) NOT NULL CHECK(action IN ('close','reopen','delete','restore')),
    reason VARCHAR(500) NOT NULL,
    request_key VARCHAR(100) NOT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id,request_key)
);
CREATE INDEX idx_pf_org_admin_audit_org ON pf_org_admin_audit(tenant_id,org_id,create_time);
COMMENT ON TABLE pf_org_admin_audit IS '组织关闭、启用、软删除、恢复的管理员审计记录';
