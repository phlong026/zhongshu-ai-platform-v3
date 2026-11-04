-- V20261104.820：M09-T04 直接项目费用——非材料的直接成本登记（人工/对外劳务/差旅等）。
-- 规则（权威 16）：确认即计成本；付款经 EngineeringPayablePort 挂 finance 应付（付款不重计成本）；
-- 退回减已付现金不改已确认费用；无公共费用分摊。
CREATE TABLE fr_eng_expense (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, project_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL, purpose VARCHAR(32) NOT NULL,
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0 AND amount_cents <= 1000000000000),
    payable_id BIGINT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'REGISTERED',
    note VARCHAR(500) NULL,
    author_user_id BIGINT NOT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(), update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_eng_expense PRIMARY KEY (id),
    CONSTRAINT ck_fr_eng_expense_purpose CHECK (purpose IN ('LABOR', 'TRAVEL', 'OTHER')),
    CONSTRAINT ck_fr_eng_expense_status CHECK (status IN ('REGISTERED', 'CANCELLED'))
);
CREATE INDEX idx_fr_eng_expense_project ON fr_eng_expense (company_id, project_id);
