-- V20261102.814：工程全周期核心六表（阶段 2，franchise-engineering 域）
-- project/document/plan_version/plan_node/site_record/closure；规则来源 docs/backend-parity/17-阶段2工程全周期（V2.1 评审 PASS）。
-- 建模唯一基准=v3 蓝本 recordFlow 新链（经典链不建不迁移，已验证机制◆吸收：顺移/质检补发/去向见 815）。
-- 租户口径：DO @TenantIgnore + 显式 company_id（content/sales 惯例）；OrgScopeCustomizer 注册十三表。
-- 金额一律分（cents，F11/C13）；数量 NUMERIC(12,3)（蓝本三位小数）。

CREATE TABLE fr_eng_project (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL,
    customer_id BIGINT NULL, customer_name VARCHAR(64) NULL, phone VARCHAR(16) NULL,
    source_commission_id BIGINT NULL,
    source_type VARCHAR(16) NOT NULL,
    parent_project_id BIGINT NULL,
    manager_user_id BIGINT NOT NULL, assigned_engineer_user_id BIGINT NULL,
    city VARCHAR(64) NULL, area NUMERIC(10,2) NULL, floors INT NULL, name VARCHAR(128) NOT NULL,
    contract_total_cents BIGINT NOT NULL DEFAULT 0, first_cents BIGINT NOT NULL DEFAULT 0,
    contract_asset_id BIGINT NULL, contract_version INT NULL, contract_date DATE NULL,
    drawing_version VARCHAR(64) NULL,
    actual_start_at DATE NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING_START',
    prepared_note VARCHAR(1000) NULL, prepared_at TIMESTAMPTZ NULL, prepared_workers JSONB NULL,
    revision BIGINT NOT NULL DEFAULT 0, author_user_id BIGINT NOT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(), update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_eng_project PRIMARY KEY (id),
    CONSTRAINT ck_fr_eng_project_source CHECK (source_type IN ('HANDOVER', 'MANUAL', 'INCREMENT')),
    CONSTRAINT ck_fr_eng_project_status CHECK (status IN
        ('PENDING_START', 'IN_PROGRESS', 'READY_DELIVER', 'DELIVERED', 'ARCHIVED')),
    CONSTRAINT ck_fr_eng_project_cents CHECK (contract_total_cents >= 0 AND first_cents >= 0),
    CONSTRAINT ck_fr_eng_project_increment CHECK (source_type <> 'INCREMENT' OR parent_project_id IS NOT NULL)
);
-- 一业务委托至多一工程（F2 强断言；重复回调幂等由此兜底）
CREATE UNIQUE INDEX uk_fr_eng_project_commission ON fr_eng_project (company_id, source_commission_id)
    WHERE source_commission_id IS NOT NULL;
-- 手工/增项查重：同组织未归档项目名唯一（蓝本口径略宽：归档名可复用，D-3 设计选择）
CREATE UNIQUE INDEX uk_fr_eng_project_name ON fr_eng_project (company_id, name)
    WHERE status <> 'ARCHIVED';
CREATE INDEX idx_fr_eng_project_org ON fr_eng_project (company_id, status, update_time);

CREATE TABLE fr_eng_document (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, project_id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    file_ref VARCHAR(1024) NOT NULL,
    kind VARCHAR(16) NOT NULL, version VARCHAR(64) NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_eng_document PRIMARY KEY (id),
    CONSTRAINT ck_fr_eng_document_kind CHECK (kind IN ('DRAWING', 'DOCUMENT'))
);
CREATE INDEX idx_fr_eng_document_project ON fr_eng_document (company_id, project_id);

-- 计划版本快照（D-5：保存计划=版本+节点整体重建，旧版留快照；source=TEMPLATE|MANUAL，◆经典链字段保留）
CREATE TABLE fr_eng_plan_version (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, project_id BIGINT NOT NULL,
    version INT NOT NULL, nodes JSONB NOT NULL,
    source VARCHAR(16) NOT NULL, compiled_at TIMESTAMPTZ NULL, author_user_id BIGINT NOT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_eng_plan_version PRIMARY KEY (id),
    CONSTRAINT ck_fr_eng_plan_version_source CHECK (source IN ('TEMPLATE', 'MANUAL')),
    CONSTRAINT uk_fr_eng_plan_version UNIQUE (project_id, version)
);
CREATE INDEX idx_fr_eng_plan_version_project ON fr_eng_plan_version (company_id, project_id, version);

-- 当前节点行表（corrections/reopen_history 留痕 JSONB）
CREATE TABLE fr_eng_plan_node (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, project_id BIGINT NOT NULL,
    node_index INT NOT NULL, name VARCHAR(100) NOT NULL,
    start_date DATE NULL, end_date DATE NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    milestone VARCHAR(16) NULL,
    responsible_user_id BIGINT NULL, completed_at DATE NULL,
    corrections JSONB NULL, reopen_history JSONB NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(), update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_eng_plan_node PRIMARY KEY (id),
    CONSTRAINT ck_fr_eng_plan_node_status CHECK (status IN ('PENDING', 'IN_PROGRESS', 'DONE')),
    CONSTRAINT ck_fr_eng_plan_node_milestone CHECK (milestone IS NULL OR milestone = 'TOPPING'),
    CONSTRAINT ck_fr_eng_plan_node_dates CHECK (start_date IS NULL OR end_date IS NULL OR end_date >= start_date),
    CONSTRAINT uk_fr_eng_plan_node UNIQUE (project_id, node_index)
);
CREATE INDEX idx_fr_eng_plan_node_project ON fr_eng_plan_node (company_id, project_id, node_index);

-- 现场记录三形态：普通/节点完成留痕（completion=true, result=PASS, review_mode=SINGLE_PERSON_RECORD，D-9）/更正留痕
CREATE TABLE fr_eng_site_record (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, project_id BIGINT NOT NULL,
    node_id BIGINT NULL, occurred_at DATE NOT NULL, note VARCHAR(2000) NOT NULL,
    completion BOOLEAN NOT NULL DEFAULT FALSE,
    result VARCHAR(16) NULL, review_mode VARCHAR(32) NULL,
    file_asset_ids JSONB NULL, corrections JSONB NULL,
    author_user_id BIGINT NOT NULL, create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_eng_site_record PRIMARY KEY (id),
    CONSTRAINT ck_fr_eng_site_record_result CHECK (result IS NULL OR result = 'PASS'),
    CONSTRAINT ck_fr_eng_site_record_mode CHECK (review_mode IS NULL OR review_mode = 'SINGLE_PERSON_RECORD')
);
CREATE INDEX idx_fr_eng_site_record_project ON fr_eng_site_record (company_id, project_id, node_id, occurred_at);

-- 尾段三段（1:1；deliver→settle→archive 前置链 D-11；字段组同置约束）
CREATE TABLE fr_eng_closure (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, project_id BIGINT NOT NULL,
    delivered_at DATE NULL, recipient VARCHAR(64) NULL,
    owner_asset_id BIGINT NULL, file_asset_id BIGINT NULL, deliver_note VARCHAR(1000) NULL,
    delivered_by BIGINT NULL,
    settled_at DATE NULL, settle_note VARCHAR(1000) NULL, settle_asset_id BIGINT NULL, settled_by BIGINT NULL,
    archived_at TIMESTAMPTZ NULL, archived_by BIGINT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(), update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_eng_closure PRIMARY KEY (id),
    CONSTRAINT uk_fr_eng_closure UNIQUE (project_id),
    CONSTRAINT ck_fr_eng_closure_deliver CHECK (
        (delivered_at IS NULL AND recipient IS NULL AND file_asset_id IS NULL)
        OR (delivered_at IS NOT NULL AND recipient IS NOT NULL AND file_asset_id IS NOT NULL)),
    CONSTRAINT ck_fr_eng_closure_order CHECK (
        archived_at IS NULL OR (settled_at IS NOT NULL AND delivered_at IS NOT NULL))
);
