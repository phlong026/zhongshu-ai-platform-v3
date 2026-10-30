-- 平台基准价版本（2026-10-20）
-- 管理员把某一地区当前生效的 PUBLISHED 基准价批量快照为一个版本；
-- 用户新建地区报价配置时引用最新版本，之后可手动同步到新版本。
CREATE TABLE budget_price_book_version (
    id BIGINT CONSTRAINT pk_budget_price_book_version PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    region_id BIGINT NOT NULL,
    region_code VARCHAR(32) NOT NULL,
    version INTEGER NOT NULL CONSTRAINT ck_budget_price_book_version_positive CHECK (version > 0),
    note VARCHAR(200),
    items JSONB NOT NULL CONSTRAINT ck_budget_price_book_items_object CHECK (jsonb_typeof(items) = 'object'),
    item_count INTEGER NOT NULL CONSTRAINT ck_budget_price_book_item_count CHECK (item_count >= 0),
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_budget_price_book_version UNIQUE (tenant_id, region_id, version),
    CONSTRAINT fk_budget_price_book_region FOREIGN KEY (tenant_id, region_id) REFERENCES budget_region (tenant_id, id)
);

CREATE INDEX idx_budget_price_book_region_version
    ON budget_price_book_version (tenant_id, region_id, version DESC);

ALTER TABLE budget_region
    ADD COLUMN price_book_version INTEGER NOT NULL DEFAULT 0
        CHECK (price_book_version >= 0);

ALTER TABLE budget_user_region
    ADD COLUMN base_version INTEGER NOT NULL DEFAULT 0
        CHECK (base_version >= 0);
