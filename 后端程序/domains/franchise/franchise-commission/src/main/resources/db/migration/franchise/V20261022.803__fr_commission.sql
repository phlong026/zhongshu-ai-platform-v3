-- V20261022.803：加盟协同域设计委托（M2.4，sales-offline-v1）
-- 规则来源：小程序 sales-flow.js（状态机）、commercial-approval.js（费用审批）、
-- transfers-v2.js（首尾款条件）、authority-v2.js（对象规则）。
-- 全部表带 company_id（归属门店），由平台 OrgScopeRule 按当前工作身份所在组织自动过滤；
-- 总部需要读的表另带 design_org_id；不带 tenant_id。

-- 标准设计费用：只追加，每次保存一个新版本；取本门店 version 最大且 active 的版本匹配
CREATE TABLE fr_design_policy
(
    id                 BIGINT       NOT NULL,
    company_id         BIGINT       NOT NULL,
    version            INT          NOT NULL,
    total_cents        BIGINT       NOT NULL,
    first_cents        BIGINT       NOT NULL,
    scope              VARCHAR(500) NOT NULL,
    recipient          VARCHAR(64)  NOT NULL,
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    approved_by_user_id  BIGINT     NOT NULL,
    approved_by_post_id BIGINT     NOT NULL,
    create_time        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_design_policy PRIMARY KEY (id),
    CONSTRAINT ck_fr_design_policy_amount CHECK (first_cents > 0 AND total_cents > first_cents),
    CONSTRAINT uk_fr_design_policy_version UNIQUE (company_id, version)
);

-- 委托：一个客户一条（首次提交创建）
CREATE TABLE fr_commission
(
    id                  BIGINT      NOT NULL,
    company_id          BIGINT      NOT NULL,
    design_org_id       BIGINT      NOT NULL,
    customer_id         BIGINT      NOT NULL,
    customer_name       VARCHAR(64) NOT NULL,
    status              VARCHAR(24) NOT NULL,
    submission_version  INT         NOT NULL,
    dispatch_seq        INT         NULL,
    handover_version    INT         NULL,
    first_ready_at      TIMESTAMPTZ NULL,
    tail_paid_at        TIMESTAMPTZ NULL,
    tail_ready_at       TIMESTAMPTZ NULL,
    revision            BIGINT      NOT NULL DEFAULT 1,
    create_time         TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_commission PRIMARY KEY (id),
    CONSTRAINT uk_fr_commission_customer UNIQUE (customer_id),
    CONSTRAINT ck_fr_commission_status CHECK (status IN ('FEE_PENDING', 'FEE_RETURNED', 'DESIGNING',
                                                         'DESIGN_COMPLETED', 'DISPATCHED',
                                                         'HANDOVER_SUBMITTED', 'HANDOVER_RETURNED', 'ACCEPTED'))
);
CREATE INDEX idx_fr_commission_company_status ON fr_commission (company_id, status);
CREATE INDEX idx_fr_commission_design_org ON fr_commission (design_org_id, status);

-- 每次提交（首次、费用退回后重提）
CREATE TABLE fr_commission_submission
(
    id                   BIGINT        NOT NULL,
    company_id           BIGINT        NOT NULL,
    design_org_id        BIGINT        NOT NULL,
    commission_id        BIGINT        NOT NULL,
    version              INT           NOT NULL,
    text                 VARCHAR(2000) NOT NULL,
    scope                VARCHAR(500)  NOT NULL,
    attachment           TEXT          NULL,
    budget_version       INT           NULL,
    plan_version         INT           NULL,
    facade_version       INT           NULL,
    width                NUMERIC(6, 2) NULL,
    depth                NUMERIC(6, 2) NULL,
    floors               INT           NULL,
    area                 NUMERIC(8, 2) NULL,
    scheme_snapshot      TEXT          NULL,
    total_cents          BIGINT        NOT NULL,
    first_cents          BIGINT        NOT NULL,
    recipient            VARCHAR(64)   NOT NULL,
    auth_kind            VARCHAR(16)   NOT NULL,
    policy_id            BIGINT        NULL,
    policy_version       INT           NULL,
    submitted_by_user_id BIGINT        NOT NULL,
    submitted_by_post_id BIGINT        NOT NULL,
    create_time          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_commission_submission PRIMARY KEY (id),
    CONSTRAINT fk_fr_commission_submission_commission FOREIGN KEY (commission_id) REFERENCES fr_commission (id),
    CONSTRAINT ck_fr_commission_submission_auth CHECK (auth_kind IN ('STANDARD', 'EXCEPTION')),
    CONSTRAINT ck_fr_commission_submission_amount CHECK (first_cents > 0 AND total_cents > first_cents),
    CONSTRAINT uk_fr_commission_submission_version UNIQUE (commission_id, version)
);
CREATE INDEX idx_fr_commission_submission_commission ON fr_commission_submission (commission_id, version);

-- 设计行政记录与修改意见
CREATE TABLE fr_commission_progress
(
    id              BIGINT        NOT NULL,
    company_id      BIGINT        NOT NULL,
    design_org_id   BIGINT        NOT NULL,
    commission_id   BIGINT        NOT NULL,
    kind            VARCHAR(16)   NOT NULL,
    seq             INT           NOT NULL,
    note            VARCHAR(1000) NOT NULL,
    recipient       VARCHAR(64)   NULL,
    feedback_status VARCHAR(16)   NULL,
    dispatch_seq    INT           NULL,
    actor_user_id   BIGINT        NOT NULL,
    actor_post_id   BIGINT        NOT NULL,
    create_time     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_commission_progress PRIMARY KEY (id),
    CONSTRAINT fk_fr_commission_progress_commission FOREIGN KEY (commission_id) REFERENCES fr_commission (id),
    CONSTRAINT ck_fr_commission_progress_kind CHECK (kind IN ('COMPLETE', 'DISPATCH', 'FEEDBACK')),
    CONSTRAINT ck_fr_commission_progress_feedback CHECK (kind <> 'FEEDBACK' OR feedback_status IN ('PENDING', 'RESOLVED')),
    CONSTRAINT uk_fr_commission_progress_seq UNIQUE (commission_id, kind, seq)
);

-- 成果与移交：每次提交一版，工程负责人接收或退回
CREATE TABLE fr_commission_handover
(
    id                   BIGINT        NOT NULL,
    company_id           BIGINT        NOT NULL,
    commission_id        BIGINT        NOT NULL,
    version              INT           NOT NULL,
    dispatch_seq         INT           NOT NULL,
    result_file          TEXT          NOT NULL,
    drawing_file         TEXT          NOT NULL,
    review_file          TEXT          NOT NULL,
    confirmation_file    TEXT          NOT NULL,
    drawing_version      VARCHAR(64)   NOT NULL,
    confirmation_date    DATE          NOT NULL,
    note                 VARCHAR(1000) NOT NULL,
    manager_user_id      BIGINT        NOT NULL,
    status               VARCHAR(16)   NOT NULL,
    decision_note        VARCHAR(500)  NULL,
    decided_by_user_id   BIGINT        NULL,
    decided_at           TIMESTAMPTZ   NULL,
    submitted_by_user_id BIGINT        NOT NULL,
    create_time          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_commission_handover PRIMARY KEY (id),
    CONSTRAINT fk_fr_commission_handover_commission FOREIGN KEY (commission_id) REFERENCES fr_commission (id),
    CONSTRAINT ck_fr_commission_handover_status CHECK (status IN ('PENDING', 'RETURNED', 'ACCEPTED')),
    CONSTRAINT ck_fr_commission_handover_confirmation CHECK (confirmation_date <= CURRENT_DATE),
    CONSTRAINT uk_fr_commission_handover_version UNIQUE (commission_id, version)
);
CREATE INDEX idx_fr_commission_handover_manager ON fr_commission_handover (manager_user_id, status);
