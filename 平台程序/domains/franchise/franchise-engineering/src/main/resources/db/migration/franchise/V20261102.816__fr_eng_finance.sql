-- V20261102.816：工程财务三表（阶段 2，franchise-engineering 域）
-- collection_term/collection/followup；D-8 两期制（开工准备即固化，无审批）+ D-10 催办双维度。
-- 收款登记即已核算（C4 只建新链语义）；收款写通 finance 经 BusinessReceiptPort（receipt_id 关联）；
-- 付款本体在 finance 侧（FinPaymentService），工程端零付款表。

-- 收款约定（开工准备即固化：START=first、TOPPING=total-first；拍板无审批直接生效）
CREATE TABLE fr_eng_collection_term (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, project_id BIGINT NOT NULL,
    stage VARCHAR(16) NOT NULL,
    amount_cents BIGINT NOT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_eng_collection_term PRIMARY KEY (id),
    CONSTRAINT ck_fr_eng_collection_term_stage CHECK (stage IN ('START', 'TOPPING')),
    CONSTRAINT ck_fr_eng_collection_term_amount CHECK (amount_cents > 0),
    CONSTRAINT uk_fr_eng_collection_term UNIQUE (project_id, stage)
);
CREATE INDEX idx_fr_eng_collection_term_org ON fr_eng_collection_term (company_id, project_id);

-- 收款登记（本期累计≤应收 006；流水全公司唯一；TOPPING 期须封顶节点已完成——收款侧前置）
CREATE TABLE fr_eng_collection (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, project_id BIGINT NOT NULL,
    stage VARCHAR(16) NOT NULL,
    amount_cents BIGINT NOT NULL,
    transaction_no VARCHAR(128) NOT NULL, occurred_at DATE NOT NULL,
    file_asset_id BIGINT NOT NULL, receipt_id BIGINT NULL,
    purpose VARCHAR(16) NOT NULL,
    registrar_user_id BIGINT NOT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_eng_collection PRIMARY KEY (id),
    CONSTRAINT ck_fr_eng_collection_stage CHECK (stage IN ('START', 'TOPPING')),
    CONSTRAINT ck_fr_eng_collection_purpose CHECK (purpose IN ('工程预收款', '工程尾款')),
    CONSTRAINT ck_fr_eng_collection_amount CHECK (amount_cents > 0)
);
CREATE UNIQUE INDEX uk_fr_eng_collection_txn ON fr_eng_collection (company_id, transaction_no);
CREATE INDEX idx_fr_eng_collection_project ON fr_eng_collection (company_id, project_id, stage);

-- 催办双维度：COLLECTION=stage 维度（同日同内容幂等）；MATERIAL=material_plan_id 维度（蓝本挂计划单）
CREATE TABLE fr_eng_followup (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, project_id BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    stage VARCHAR(16) NULL, material_plan_id BIGINT NULL,
    promise_date DATE NULL, note VARCHAR(500) NOT NULL,
    author_user_id BIGINT NOT NULL, create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_eng_followup PRIMARY KEY (id),
    CONSTRAINT ck_fr_eng_followup_kind CHECK (kind IN ('COLLECTION', 'MATERIAL')),
    CONSTRAINT ck_fr_eng_followup_stage CHECK (stage IS NULL OR stage IN ('START', 'TOPPING')),
    CONSTRAINT ck_fr_eng_followup_shape CHECK (
        (kind = 'COLLECTION' AND stage IS NOT NULL AND material_plan_id IS NULL)
        OR (kind = 'MATERIAL' AND stage IS NULL AND material_plan_id IS NOT NULL))
);
CREATE INDEX idx_fr_eng_followup_project ON fr_eng_followup (company_id, project_id, kind, create_time);
