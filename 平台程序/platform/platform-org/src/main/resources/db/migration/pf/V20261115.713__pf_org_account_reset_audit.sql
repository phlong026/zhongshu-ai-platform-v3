ALTER TABLE pf_org_admin_audit DROP CONSTRAINT pf_org_admin_audit_action_check;
ALTER TABLE pf_org_admin_audit ADD CONSTRAINT pf_org_admin_audit_action_check
    CHECK(action IN ('close','reopen','delete','restore','reset-password'));
COMMENT ON TABLE pf_org_admin_audit IS '组织状态及加盟商账号密码重置审计；不保存密码';
