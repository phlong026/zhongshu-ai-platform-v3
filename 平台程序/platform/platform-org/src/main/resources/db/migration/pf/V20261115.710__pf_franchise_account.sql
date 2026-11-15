-- 登录别名只映射已有 system_users.user_id，不产生第二套用户或积分账户。
CREATE TABLE pf_franchise_account (
    account_id VARCHAR(36) PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    org_id BIGINT NOT NULL REFERENCES pf_org_unit(dept_id),
    user_id BIGINT NOT NULL,
    login_name VARCHAR(100) NOT NULL,
    org_name VARCHAR(128) NOT NULL,
    owner_name VARCHAR(40) NOT NULL,
    account_type VARCHAR(8) NOT NULL DEFAULT 'BOSS' CHECK(account_type IN ('BOSS','STAFF')),
    brand VARCHAR(40) NOT NULL,
    province VARCHAR(40) NOT NULL,
    city VARCHAR(40) NOT NULL,
    area VARCHAR(40) NOT NULL,
    area_pinyin VARCHAR(80) NOT NULL,
    owner_pinyin VARCHAR(80) NOT NULL,
    remark VARCHAR(500),
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id, login_name),
    UNIQUE(tenant_id, org_id, user_id)
);
CREATE UNIQUE INDEX uk_pf_franchise_boss_name ON pf_franchise_account(tenant_id, org_name) WHERE account_type='BOSS';
CREATE INDEX idx_pf_franchise_account_user ON pf_franchise_account(tenant_id, user_id);
CREATE TABLE pf_franchise_account_alias (
    tenant_id BIGINT NOT NULL,
    account_id VARCHAR(36) NOT NULL REFERENCES pf_franchise_account(account_id),
    alias VARCHAR(100) NOT NULL,
    PRIMARY KEY(tenant_id, account_id, alias)
);
CREATE INDEX idx_pf_franchise_account_alias_search ON pf_franchise_account_alias(tenant_id, alias text_pattern_ops);
CREATE TABLE pf_staff_password_policy (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    require_change BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY(tenant_id, user_id)
);
COMMENT ON TABLE pf_franchise_account IS '加盟商开通资料与正式登录别名，用户主体为system_users';
COMMENT ON TABLE pf_franchise_account_alias IS '可重复的登录检索简称；需密码或已绑定微信验证';
COMMENT ON TABLE pf_staff_password_policy IS '用户级初始密码状态；不保存明文密码';
