-- 账户绑定变更审计（2026-10-11 优化建议④）：管理端修正绑定信息时留痕旧值/新值，
-- 供对账与纠纷追溯（当前 updateBinding 为覆盖写，无历史不可回查）。
-- id 由应用层雪花赋值（与 access_code_redemption 一致，表无序列默认）。
CREATE TABLE IF NOT EXISTS access_code_binding_audit (
    id BIGINT NOT NULL PRIMARY KEY,
    code_id BIGINT NOT NULL,
    actor_id VARCHAR(32) NOT NULL,
    old_company_name VARCHAR(120),
    old_contact_name VARCHAR(60),
    old_contact_phone VARCHAR(30),
    new_company_name VARCHAR(120),
    new_contact_name VARCHAR(60),
    new_contact_phone VARCHAR(30),
    tenant_id BIGINT NOT NULL DEFAULT 0,
    creator VARCHAR(64) DEFAULT '',
    create_time TIMESTAMP NOT NULL DEFAULT now(),
    updater VARCHAR(64) DEFAULT '',
    update_time TIMESTAMP NOT NULL DEFAULT now(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_binding_audit_code ON access_code_binding_audit(code_id);
