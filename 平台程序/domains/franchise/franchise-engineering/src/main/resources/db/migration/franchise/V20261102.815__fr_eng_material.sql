-- V20261102.815：工程材料四表（阶段 2，franchise-engineering 域）
-- material/supply/supply_receipt/material_movement；D-6 材料四实体。
-- ◆吸收自经典链并固化为后端模型：收货质检（OK/REJECTED + quality_resolution 更换后合格）、
--   差异补发（RESHIP 行，数量=应收-实收）、材料去向（CONSUME/DAMAGE/RETURN/SURPLUS_TRANSFER）。
-- 供应态对齐蓝本三态 PENDING_TERMS/READY_FINANCE/CANCELLED（到货态由 receipts 派生）；
-- payer 枚举保留三方=设计超前（蓝本 recordFlow 固定 STORE）。

-- 材料计划：material_plan_id=计划单分组（同批提交共享；调整/材料催办挂该维度，D-6/D-10）
CREATE TABLE fr_eng_material (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, project_id BIGINT NOT NULL,
    material_plan_id BIGINT NOT NULL, node_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL, spec VARCHAR(200) NULL, unit VARCHAR(16) NOT NULL,
    quantity NUMERIC(12,3) NOT NULL,
    required_date DATE NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED',
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_eng_material PRIMARY KEY (id),
    CONSTRAINT ck_fr_eng_material_status CHECK (status IN ('SUBMITTED', 'CANCELLED')),
    CONSTRAINT ck_fr_eng_material_quantity CHECK (quantity >= 0)
);
CREATE INDEX idx_fr_eng_material_project ON fr_eng_material (company_id, project_id, material_plan_id);
CREATE INDEX idx_fr_eng_material_node ON fr_eng_material (company_id, node_id);

-- 供应：terms 字段组（供应商/单价/运费/依据）+ 财务应付锚（payable_source_ref）；terms 后已付款锁定（paid 快照+实时 Port 复核）
CREATE TABLE fr_eng_supply (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, project_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL, quantity NUMERIC(12,3) NOT NULL,
    supplier VARCHAR(100) NULL, contact VARCHAR(64) NULL, promise_date DATE NULL,
    unit_price_cents BIGINT NOT NULL DEFAULT 0, freight_cents BIGINT NOT NULL DEFAULT 0,
    payer VARCHAR(16) NOT NULL DEFAULT 'STORE',
    basis VARCHAR(1024) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING_TERMS',
    terms_user_id BIGINT NULL, terms_at TIMESTAMPTZ NULL,
    paid BOOLEAN NOT NULL DEFAULT FALSE, payable_id BIGINT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(), update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_eng_supply PRIMARY KEY (id),
    CONSTRAINT ck_fr_eng_supply_status CHECK (status IN ('PENDING_TERMS', 'READY_FINANCE', 'CANCELLED')),
    CONSTRAINT ck_fr_eng_supply_payer CHECK (payer IN ('STORE', 'HQ', 'OWNER_DIRECT')),
    CONSTRAINT ck_fr_eng_supply_cents CHECK (unit_price_cents >= 0 AND freight_cents >= 0),
    CONSTRAINT ck_fr_eng_supply_quantity CHECK (quantity >= 0)
);
CREATE INDEX idx_fr_eng_supply_project ON fr_eng_supply (company_id, project_id);
CREATE INDEX idx_fr_eng_supply_material ON fr_eng_supply (company_id, material_id);

-- 收货与补发（统一表）：RECEIPT=收货（quality 质检+quality_resolution 闭环）；RESHIP=差异自动补发行
-- （parent_receipt_id 挂原收货，expected_quantity=应收-实收）；transaction_no 全公司唯一索引（005）
CREATE TABLE fr_eng_supply_receipt (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, project_id BIGINT NOT NULL,
    supply_id BIGINT NOT NULL, kind VARCHAR(16) NOT NULL, parent_receipt_id BIGINT NULL,
    transaction_no VARCHAR(128) NOT NULL, batch_code VARCHAR(64) NULL,
    expected_quantity NUMERIC(12,3) NULL, actual_quantity NUMERIC(12,3) NULL,
    quality VARCHAR(16) NULL,
    quality_resolution JSONB NULL,
    occurred_at DATE NOT NULL, file_asset_id BIGINT NOT NULL,
    note VARCHAR(500) NULL, corrections JSONB NULL,
    author_user_id BIGINT NOT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(), update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_eng_supply_receipt PRIMARY KEY (id),
    CONSTRAINT ck_fr_eng_supply_receipt_kind CHECK (kind IN ('RECEIPT', 'RESHIP')),
    CONSTRAINT ck_fr_eng_supply_receipt_quality CHECK (quality IS NULL OR quality IN ('OK', 'REJECTED')),
    CONSTRAINT ck_fr_eng_supply_receipt_reship CHECK (kind <> 'RESHIP' OR parent_receipt_id IS NOT NULL)
);
CREATE UNIQUE INDEX uk_fr_eng_supply_receipt_txn ON fr_eng_supply_receipt (company_id, transaction_no);
CREATE INDEX idx_fr_eng_supply_receipt_supply ON fr_eng_supply_receipt (company_id, supply_id, occurred_at);

-- ◆材料去向（经典链吸收）：领用耗用/报损/退回/余料结转；q ≤ 合格到货-已分配（服务层校验）
CREATE TABLE fr_eng_material_movement (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, project_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL, kind VARCHAR(20) NOT NULL,
    quantity NUMERIC(12,3) NOT NULL,
    settlement_status VARCHAR(16) NOT NULL DEFAULT 'REGISTERED',
    receiver_user_id BIGINT NULL, note VARCHAR(500) NULL,
    author_user_id BIGINT NOT NULL, create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_eng_material_movement PRIMARY KEY (id),
    CONSTRAINT ck_fr_eng_material_movement_kind CHECK (kind IN ('CONSUME', 'DAMAGE', 'RETURN', 'SURPLUS_TRANSFER')),
    CONSTRAINT ck_fr_eng_material_movement_settle CHECK (settlement_status IN ('PENDING_SETTLE', 'REGISTERED')),
    CONSTRAINT ck_fr_eng_material_movement_quantity CHECK (quantity > 0)
);
CREATE INDEX idx_fr_eng_material_movement_project ON fr_eng_material_movement (company_id, project_id, material_id);
