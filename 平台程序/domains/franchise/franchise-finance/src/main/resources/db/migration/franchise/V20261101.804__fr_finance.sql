-- V20261101.804：财务账簿与到账（B1，franchise-finance 域）
-- 金额一律分（BIGINT CHECK >0 且 ≤10^12）；状态用 CHECK 枚举；交易身份由 fr_fin_txn_no 永久承载（软删不释放）。
-- 单表组织隔离字段 company_id 显式出现在全部查询；表带 tenant_id 默认 1（域内 @TenantIgnore，隔离靠 company_id）。

CREATE TABLE fr_fin_receipt (
    id                BIGINT       NOT NULL,
    company_id        BIGINT       NOT NULL,
    project_id        BIGINT       NULL,
    customer_id       BIGINT       NULL,
    source_type       VARCHAR(16)  NOT NULL CHECK (source_type IN ('business','engineering','intercompany','design','manual')),
    source_ref        VARCHAR(128) NULL,
    name              VARCHAR(128) NOT NULL,
    amount_cents      BIGINT       NOT NULL CHECK (amount_cents > 0 AND amount_cents <= 1000000000000),
    status            VARCHAR(16)  NOT NULL DEFAULT '已登记' CHECK (status IN ('待登记','已登记','已撤销')),
    received_date     DATE         NULL,
    registered_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    registrar_user_id BIGINT       NOT NULL,
    method            VARCHAR(16)  NOT NULL DEFAULT 'bank' CHECK (method IN ('bank','cash','other')),
    transaction_no    VARCHAR(64)  NULL,
    voucher_file_id   BIGINT       NULL,
    revision_version  INT          NOT NULL DEFAULT 1,
    void_reason       VARCHAR(255) NULL,
    void_by_user_id   BIGINT       NULL,
    tenant_id         BIGINT       NOT NULL DEFAULT 1,
    creator           VARCHAR(64)  NULL,
    create_time       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater           VARCHAR(64)  NULL,
    update_time       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted           BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_fr_fin_receipt PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_fr_fin_receipt_txn ON fr_fin_receipt (company_id, transaction_no) WHERE transaction_no IS NOT NULL;
CREATE INDEX idx_fr_fin_receipt_org ON fr_fin_receipt (company_id, status, create_time DESC);
CREATE INDEX idx_fr_fin_receipt_source ON fr_fin_receipt (source_type, source_ref);

CREATE TABLE fr_fin_receipt_revision (
    id                    BIGINT      NOT NULL,
    receipt_id            BIGINT      NOT NULL,
    base_revision_version INT         NOT NULL,
    original_amount_cents BIGINT      NOT NULL,
    new_amount_cents      BIGINT      NOT NULL CHECK (new_amount_cents > 0 AND new_amount_cents <= 1000000000000),
    note                  VARCHAR(255) NULL,
    operator_user_id      BIGINT      NOT NULL,
    tenant_id             BIGINT      NOT NULL DEFAULT 1,
    creator               VARCHAR(64) NULL,
    create_time           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_fin_receipt_revision PRIMARY KEY (id),
    CONSTRAINT uk_fr_fin_revision_base UNIQUE (receipt_id, base_revision_version)
);
CREATE INDEX idx_fr_fin_revision_receipt ON fr_fin_receipt_revision (receipt_id, create_time DESC);

CREATE TABLE fr_fin_payable (
    id            BIGINT       NOT NULL,
    company_id    BIGINT       NOT NULL,
    project_id    BIGINT       NULL,
    stage         VARCHAR(64)  NULL,
    total_cents   BIGINT       NOT NULL CHECK (total_cents > 0 AND total_cents <= 1000000000000),
    recipient     VARCHAR(128) NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT '进行中' CHECK (status IN ('进行中','已结清')),
    agreement_ref VARCHAR(128) NULL,
    tenant_id     BIGINT       NOT NULL DEFAULT 1,
    creator       VARCHAR(64)  NULL,
    create_time   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater       VARCHAR(64)  NULL,
    update_time   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_fr_fin_payable PRIMARY KEY (id)
);
CREATE INDEX idx_fr_fin_payable_org ON fr_fin_payable (company_id, status, create_time DESC);

CREATE TABLE fr_fin_payment (
    id              BIGINT       NOT NULL,
    payable_id      BIGINT       NOT NULL,
    project_id      BIGINT       NULL,
    company_id      BIGINT       NOT NULL,
    amount_cents    BIGINT       NOT NULL CHECK (amount_cents > 0 AND amount_cents <= 1000000000000),
    status          VARCHAR(16)  NOT NULL DEFAULT '已核实' CHECK (status IN ('已核实','已作废')),
    pay_date        DATE         NULL,
    operator_user_id BIGINT      NOT NULL,
    transaction_no  VARCHAR(64)  NULL,
    voucher_file_id BIGINT       NULL,
    void_by_user_id BIGINT       NULL,
    void_reason     VARCHAR(255) NULL,
    voided_at       TIMESTAMPTZ  NULL,
    tenant_id       BIGINT       NOT NULL DEFAULT 1,
    creator         VARCHAR(64)  NULL,
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater         VARCHAR(64)  NULL,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_fr_fin_payment PRIMARY KEY (id),
    CONSTRAINT ck_fr_fin_payment_void CHECK ((status = '已作废') = (voided_at IS NOT NULL))
);
-- 付款/退款交易号唯一由 fr_fin_txn_no 身份表永久承载（更正链同号重登需要），业务表不设唯一索引
CREATE INDEX idx_fr_fin_payment_payable ON fr_fin_payment (payable_id, status);

CREATE TABLE fr_fin_refund (
    id              BIGINT       NOT NULL,
    receipt_id      BIGINT       NOT NULL,
    company_id      BIGINT       NOT NULL,
    amount_cents    BIGINT       NOT NULL CHECK (amount_cents > 0 AND amount_cents <= 1000000000000),
    status          VARCHAR(16)  NOT NULL DEFAULT '已核实' CHECK (status IN ('已核实','已作废')),
    reason          VARCHAR(255) NOT NULL,
    operator_user_id BIGINT      NOT NULL,
    replaced_by_id  BIGINT       NULL,   -- 替代权 CAS 标记：被谁替代（UNIQUE 保证一记录至多一个替代）
    replaces_id     BIGINT       NULL,   -- 本记录替代的被作废记录
    date            DATE         NULL,
    transaction_no  VARCHAR(64)  NULL,
    voucher_file_id BIGINT       NULL,
    void_by_user_id BIGINT       NULL,
    void_reason     VARCHAR(255) NULL,
    voided_at       TIMESTAMPTZ  NULL,
    tenant_id       BIGINT       NOT NULL DEFAULT 1,
    creator         VARCHAR(64)  NULL,
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater         VARCHAR(64)  NULL,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_fr_fin_refund PRIMARY KEY (id),
    CONSTRAINT ck_fr_fin_refund_void CHECK ((status = '已作废') = (voided_at IS NOT NULL))
);
CREATE UNIQUE INDEX uk_fr_fin_refund_replaced_by ON fr_fin_refund (replaced_by_id) WHERE replaced_by_id IS NOT NULL;
CREATE INDEX idx_fr_fin_refund_receipt ON fr_fin_refund (receipt_id, status);

-- 交易身份：跨 receipt/payment/refund 的流水号永久防重（软删/作废都不释放）；更正链经 owner 原子切换
CREATE TABLE fr_fin_txn_no (
    id          BIGINT      NOT NULL,
    company_id  BIGINT      NOT NULL,
    txn_no      VARCHAR(64) NOT NULL,
    owner_type  VARCHAR(16) NOT NULL CHECK (owner_type IN ('receipt','payment','refund')),
    owner_id    BIGINT      NOT NULL,
    closed      BOOLEAN     NOT NULL DEFAULT FALSE,  -- 更正换号时旧身份行关闭
    tenant_id   BIGINT      NOT NULL DEFAULT 1,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_fin_txn_no PRIMARY KEY (id),
    CONSTRAINT uk_fr_fin_txn_no UNIQUE (company_id, txn_no)
);

CREATE TABLE fr_fin_bank_flow (
    id          BIGINT       NOT NULL,
    company_id  BIGINT       NOT NULL,
    bank_ref    VARCHAR(64)  NOT NULL,
    amount_cents BIGINT      NOT NULL CHECK (amount_cents > 0),
    bank_date   DATE         NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT '待匹配' CHECK (status IN ('待匹配','已认领')),
    source      VARCHAR(16)  NOT NULL DEFAULT 'dev' CHECK (source IN ('dev','e2e','import')),
    tenant_id   BIGINT       NOT NULL DEFAULT 1,
    create_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_fin_bank_flow PRIMARY KEY (id),
    CONSTRAINT uk_fr_fin_bank_flow_ref UNIQUE (company_id, bank_ref)
);

CREATE TABLE fr_fin_bank_confirm (
    id            BIGINT      NOT NULL,
    bank_flow_id  BIGINT      NOT NULL,
    receipt_id    BIGINT      NOT NULL,
    company_id    BIGINT      NOT NULL,
    amount_cents  BIGINT      NOT NULL,
    matched_by_user_id BIGINT NOT NULL,
    matched_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    tenant_id     BIGINT      NOT NULL DEFAULT 1,
    create_time   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_fin_bank_confirm PRIMARY KEY (id),
    CONSTRAINT uk_fr_fin_confirm_flow UNIQUE (bank_flow_id),
    CONSTRAINT uk_fr_fin_confirm_receipt UNIQUE (receipt_id)
);

CREATE TABLE fr_fin_match_attempt (
    id               BIGINT       NOT NULL,
    bank_flow_id     BIGINT       NOT NULL,
    tried_receipt_id BIGINT       NULL,
    reason           VARCHAR(255) NOT NULL,
    tenant_id        BIGINT       NOT NULL DEFAULT 1,
    create_time      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_fin_match_attempt PRIMARY KEY (id)
);

CREATE TABLE fr_fin_file (
    id               BIGINT       NOT NULL,
    company_id       BIGINT       NOT NULL,
    file_name        VARCHAR(255) NOT NULL,
    storage_key      VARCHAR(255) NOT NULL,
    content_type     VARCHAR(64)  NULL,
    size_bytes       BIGINT       NOT NULL DEFAULT 0,
    sha256           CHAR(64)     NULL,
    uploaded_by_user_id BIGINT   NOT NULL,
    tenant_id        BIGINT       NOT NULL DEFAULT 1,
    create_time      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_fin_file PRIMARY KEY (id)
);
CREATE INDEX idx_fr_fin_file_org ON fr_fin_file (company_id, create_time DESC);
