-- V20261102.808：业务交易流五表（B2 板块 B，franchise-sales 域）
-- 需求登记/报价/合同/回款计划/交付验收；规则来源 docs/backend-parity/06-B2板块B业务交易流-需求与验收.md（Codex 八轮评审 PASS）。
-- 租户口径：DO @TenantIgnore + 显式 company_id（crm 惯例）；OrgScopeCustomizer 注册五表。

CREATE TABLE fr_sale_requirement (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, customer_id BIGINT NOT NULL,
    version INT NOT NULL, width NUMERIC(6,2) NULL, depth NUMERIC(6,2) NULL, floors INT NULL,
    style VARCHAR(32) NULL, prompt TEXT NULL, note VARCHAR(500) NULL,
    author_user_id BIGINT NOT NULL, create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_sale_requirement PRIMARY KEY (id),
    CONSTRAINT fk_fr_sale_req_customer FOREIGN KEY (customer_id) REFERENCES fr_customer (id),
    CONSTRAINT uk_fr_sale_requirement_version UNIQUE (customer_id, version)
);
CREATE INDEX idx_fr_sale_requirement_org ON fr_sale_requirement (company_id, customer_id);

CREATE TABLE fr_sale_quote (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, customer_id BIGINT NOT NULL,
    scheme_id BIGINT NULL, scheme_kind VARCHAR(16) NULL, scheme_version INT NULL,
    items TEXT NOT NULL, total_cents BIGINT NOT NULL,
    price_version VARCHAR(64) NULL,
    customer_name VARCHAR(64) NOT NULL, customer_phone VARCHAR(16) NULL, address VARCHAR(256) NULL,
    width NUMERIC(6,2) NULL, depth NUMERIC(6,2) NULL, floors INT NULL, area NUMERIC(8,2) NULL,
    author_user_id BIGINT NOT NULL, create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater VARCHAR(64) NULL DEFAULT '', update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_fr_sale_quote PRIMARY KEY (id),
    CONSTRAINT fk_fr_sale_quote_customer FOREIGN KEY (customer_id) REFERENCES fr_customer (id),
    CONSTRAINT ck_fr_sale_quote_total CHECK (total_cents > 0 AND total_cents <= 1000000000000)
);
CREATE INDEX idx_fr_sale_quote_org ON fr_sale_quote (company_id, customer_id);

CREATE TABLE fr_sale_contract (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, customer_id BIGINT NOT NULL,
    total_cents BIGINT NOT NULL, first_cents BIGINT NOT NULL, signed_date DATE NULL,
    asset_id BIGINT NULL, status VARCHAR(16) NOT NULL DEFAULT 'WAIT_SIGN',
    revision BIGINT NOT NULL DEFAULT 1, requirement_id BIGINT NULL,
    author_user_id BIGINT NOT NULL, create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(), deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_fr_sale_contract PRIMARY KEY (id),
    CONSTRAINT fk_fr_sale_contract_customer FOREIGN KEY (customer_id) REFERENCES fr_customer (id),
    CONSTRAINT fk_fr_sale_contract_requirement FOREIGN KEY (requirement_id) REFERENCES fr_sale_requirement (id),
    CONSTRAINT ck_fr_sale_contract_status CHECK (status IN ('WAIT_SIGN','SIGNED','VOIDED')),
    CONSTRAINT ck_fr_sale_contract_first CHECK (first_cents > 0 AND first_cents <= total_cents AND total_cents <= 1000000000000)
);
CREATE UNIQUE INDEX uk_fr_sale_contract_active ON fr_sale_contract (customer_id)
    WHERE deleted = FALSE AND status <> 'VOIDED';
CREATE INDEX idx_fr_sale_contract_org ON fr_sale_contract (company_id, customer_id);

CREATE TABLE fr_sale_payment_stage (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, customer_id BIGINT NOT NULL,
    contract_id BIGINT NOT NULL, stage VARCHAR(8) NOT NULL, amount_cents BIGINT NOT NULL,
    status VARCHAR(8) NOT NULL DEFAULT 'PENDING', paid_receipt_id BIGINT NULL,
    paid_at TIMESTAMPTZ NULL, pay_fingerprint VARCHAR(64) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE, create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_sale_payment_stage PRIMARY KEY (id),
    CONSTRAINT fk_fr_sale_stage_contract FOREIGN KEY (contract_id) REFERENCES fr_sale_contract (id),
    CONSTRAINT ck_fr_sale_stage CHECK (stage IN ('first','tail')),
    CONSTRAINT ck_fr_sale_stage_status CHECK (status IN ('PENDING','PAID')),
    CONSTRAINT ck_fr_sale_stage_amount CHECK (amount_cents > 0 AND amount_cents <= 1000000000000),
    CONSTRAINT ck_fr_sale_stage_paid CHECK (
        (status = 'PENDING' AND paid_receipt_id IS NULL AND paid_at IS NULL AND pay_fingerprint IS NULL) OR
        (status = 'PAID' AND paid_receipt_id IS NOT NULL AND paid_at IS NOT NULL AND pay_fingerprint IS NOT NULL))
);
CREATE UNIQUE INDEX uk_fr_sale_stage ON fr_sale_payment_stage (contract_id, stage);
CREATE INDEX idx_fr_sale_stage_org ON fr_sale_payment_stage (company_id, status) WHERE deleted = FALSE;

CREATE TABLE fr_sale_delivery (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, customer_id BIGINT NOT NULL,
    contract_id BIGINT NOT NULL, requirement_id BIGINT NULL,
    design_delivered_at TIMESTAMPTZ NULL, design_confirmed_at TIMESTAMPTZ NULL,
    construction_delivered_at TIMESTAMPTZ NULL, construction_accepted_at TIMESTAMPTZ NULL,
    payload JSONB NULL, revision BIGINT NOT NULL DEFAULT 1,
    author_user_id BIGINT NOT NULL, create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_sale_delivery PRIMARY KEY (id),
    CONSTRAINT fk_fr_sale_delivery_contract FOREIGN KEY (contract_id) REFERENCES fr_sale_contract (id),
    CONSTRAINT fk_fr_sale_delivery_requirement FOREIGN KEY (requirement_id) REFERENCES fr_sale_requirement (id),
    CONSTRAINT ck_fr_sale_delivery_order CHECK (
        (design_confirmed_at IS NULL OR design_delivered_at IS NOT NULL) AND
        (construction_delivered_at IS NULL OR design_confirmed_at IS NOT NULL) AND
        (construction_accepted_at IS NULL OR construction_delivered_at IS NOT NULL))
);
CREATE INDEX idx_fr_sale_delivery_org ON fr_sale_delivery (company_id, contract_id);
