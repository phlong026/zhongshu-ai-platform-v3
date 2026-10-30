-- 地区报价配置（2026-10-20）
-- 目标：用户可在自己的账号下按地区维护一份显式价目（武汉价 × 系数，可逐项改），
-- 配置有版本、可复现；用户配置仅本人可见，不得进入平台正式报价。
-- 全部为追加式变更；历史预算/报价快照不改写。

-- 1) 账号覆盖价加地区维度与价格来源
ALTER TABLE budget_account_price
    ADD COLUMN region_scope VARCHAR(16) NOT NULL DEFAULT 'GLOBAL',
    ADD COLUMN region_code VARCHAR(32) NOT NULL DEFAULT '',
    ADD COLUMN price_source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN base_price_cents BIGINT,
    ADD COLUMN base_price_version INTEGER,
    ADD COLUMN coefficient NUMERIC(8,4),
    ADD COLUMN profile_version INTEGER;

-- 历史覆盖价没有地区信息：默认值即“账号通用价”（GLOBAL + 空 region_code），继续对所有地区生效。
ALTER TABLE budget_account_price
    ADD CONSTRAINT ck_budget_account_price_scope
        CHECK (region_scope IN ('GLOBAL', 'PLATFORM', 'USER')),
    ADD CONSTRAINT ck_budget_account_price_region
        CHECK ((region_scope = 'GLOBAL' AND region_code = '')
            OR (region_scope IN ('PLATFORM', 'USER') AND region_code <> '')),
    ADD CONSTRAINT ck_budget_account_price_source
        CHECK (price_source IN ('COPIED', 'COEFFICIENT', 'MANUAL'));

DROP INDEX IF EXISTS uk_budget_account_price_active;
CREATE UNIQUE INDEX uk_budget_account_price_active_scope
    ON budget_account_price (tenant_id, account_id, region_scope, region_code, option_id)
    WHERE status = 'ACTIVE' AND deleted = FALSE;

CREATE INDEX idx_budget_account_price_region
    ON budget_account_price (tenant_id, account_id, region_scope, region_code)
    WHERE deleted = FALSE;

-- 2) 自定义地区升级为“地区报价配置头”
ALTER TABLE budget_user_region
    ADD COLUMN base_region_code VARCHAR(32) NOT NULL DEFAULT 'WUHAN',
    ADD COLUMN coefficient NUMERIC(8,4) NOT NULL DEFAULT 1.0000
        CHECK (coefficient BETWEEN 0.5 AND 2.0),
    ADD COLUMN price_version INTEGER NOT NULL DEFAULT 1
        CHECK (price_version > 0),
    ADD COLUMN configured_at TIMESTAMPTZ,
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    ADD COLUMN version INTEGER NOT NULL DEFAULT 1
        CHECK (version > 0);

CREATE INDEX idx_budget_user_region_status
    ON budget_user_region (tenant_id, user_id, status)
    WHERE deleted = FALSE;

-- 3) 预算修订标记计价来源；用户价/自定义地区预算不可用于平台正式报价
ALTER TABLE budget_revision
    ADD COLUMN pricing_basis VARCHAR(16) NOT NULL DEFAULT 'PLATFORM'
        CHECK (pricing_basis IN ('PLATFORM', 'USER_PROFILE'));

-- 历史修订按冻结行快照回填：自定义地区或账号覆盖价一律视为用户自定价。
UPDATE budget_revision r
   SET pricing_basis = 'USER_PROFILE'
 WHERE EXISTS (
       SELECT 1
         FROM budget_line l
        WHERE l.revision_id = r.id
          AND l.deleted = FALSE
          AND (l.pricing_snapshot->>'regionScope' = 'USER'
            OR l.pricing_snapshot->>'priceSource' = 'ACCOUNT_OVERRIDE')
 );
