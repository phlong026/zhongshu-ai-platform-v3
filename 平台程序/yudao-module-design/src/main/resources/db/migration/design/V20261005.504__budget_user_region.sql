-- V20261005.504：用户自定义地区（2026-10-05 整改）——账号私有的建造地区，仅创建人可见可用。
-- 与平台地区（budget_region，管理员维护、全量用户共享）互补：
-- 自定义地区没有平台基准价，标准目录项价格来自本人覆盖价（budget_account_price），未填价项按「待补」口径。
CREATE TABLE budget_user_region (
    id BIGINT CONSTRAINT pk_budget_user_region PRIMARY KEY,
    user_id BIGINT NOT NULL,
    code VARCHAR(32) NOT NULL CONSTRAINT ck_budget_user_region_code CHECK (code ~ '^[A-Za-z0-9_-]+$'),
    name VARCHAR(100) NOT NULL CONSTRAINT ck_budget_user_region_name CHECK (length(trim(name)) > 0),
    tenant_id BIGINT NOT NULL DEFAULT 0,
    creator VARCHAR(64) DEFAULT '', create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater VARCHAR(64) DEFAULT '', update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_budget_user_region_tenant_user_code UNIQUE (tenant_id, user_id, code),
    CONSTRAINT uk_budget_user_region_tenant_user_name UNIQUE (tenant_id, user_id, name)
);
CREATE INDEX idx_budget_user_region_user ON budget_user_region (tenant_id, user_id) WHERE deleted = FALSE;
