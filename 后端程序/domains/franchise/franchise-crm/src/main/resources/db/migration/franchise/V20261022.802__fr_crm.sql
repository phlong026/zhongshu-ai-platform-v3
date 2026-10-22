-- V20261022.802：加盟协同域客资与客户（M2.3）
-- 规则来源：小程序 growth.js（capture、linkExisting）、business.js（客户、跟进、转交）。
-- 全部表带 company_id（归属门店），由平台 OrgScopeRule 按当前工作身份所在组织自动过滤；不带 tenant_id。

-- 客资：登记即建客户或关联原客户；只有同手机号原客户本人无权看到时，停在 LINK_REQUIRED 等负责人关联
CREATE TABLE fr_lead
(
    id                BIGINT       NOT NULL,
    company_id        BIGINT       NOT NULL,
    team_id           BIGINT       NULL,
    name              VARCHAR(64)  NOT NULL,
    phone             VARCHAR(16)  NOT NULL,
    need              VARCHAR(500) NOT NULL,
    source            VARCHAR(64)  NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    conversion        VARCHAR(16)  NULL,
    customer_id       BIGINT       NULL,
    registrar_user_id BIGINT       NOT NULL,
    registrar_post_id BIGINT       NOT NULL,
    note              VARCHAR(500) NULL,
    revision          BIGINT       NOT NULL DEFAULT 1,
    create_time       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_time       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_lead PRIMARY KEY (id),
    CONSTRAINT ck_fr_lead_status CHECK (status IN ('LINK_REQUIRED', 'CONVERTED', 'INVALID')),
    CONSTRAINT ck_fr_lead_conversion CHECK ((status = 'CONVERTED') = (conversion IS NOT NULL AND customer_id IS NOT NULL)),
    CONSTRAINT ck_fr_lead_conversion_kind CHECK (conversion IS NULL OR conversion IN ('DIRECT', 'EXISTING', 'LINKED'))
);
CREATE INDEX idx_fr_lead_company_status ON fr_lead (company_id, status);
-- 同一门店同一手机号只能有一条待关联客资
CREATE UNIQUE INDEX uk_fr_lead_pending_phone ON fr_lead (company_id, phone) WHERE status = 'LINK_REQUIRED';

-- 客户：一个门店一个手机号一个客户
CREATE TABLE fr_customer
(
    id              BIGINT       NOT NULL,
    company_id      BIGINT       NOT NULL,
    team_id         BIGINT       NULL,
    owner_user_id   BIGINT       NOT NULL,
    name            VARCHAR(64)  NOT NULL,
    phone           VARCHAR(16)  NOT NULL,
    wechat          VARCHAR(64)  NULL,
    source          VARCHAR(64)  NOT NULL,
    province        VARCHAR(32)  NULL,
    city            VARCHAR(32)  NULL,
    summary         VARCHAR(500) NULL,
    status          VARCHAR(16)  NOT NULL,
    origin_lead_id  BIGINT       NULL,
    next_visit_date DATE         NULL,
    last_follow_at  TIMESTAMPTZ  NULL,
    revision        BIGINT       NOT NULL DEFAULT 1,
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_customer PRIMARY KEY (id),
    CONSTRAINT ck_fr_customer_status CHECK (status IN ('REGISTERED', 'FOLLOWING')),
    CONSTRAINT uk_fr_customer_phone UNIQUE (company_id, phone)
);
CREATE INDEX idx_fr_customer_owner ON fr_customer (company_id, owner_user_id);
CREATE INDEX idx_fr_customer_team ON fr_customer (company_id, team_id);

-- 跟进记录：只追加
CREATE TABLE fr_customer_follow
(
    id              BIGINT       NOT NULL,
    company_id      BIGINT       NOT NULL,
    customer_id     BIGINT       NOT NULL,
    note            VARCHAR(500) NOT NULL,
    next_visit_date DATE         NULL,
    author_user_id  BIGINT       NOT NULL,
    author_post_id  BIGINT       NOT NULL,
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_customer_follow PRIMARY KEY (id),
    CONSTRAINT fk_fr_customer_follow_customer FOREIGN KEY (customer_id) REFERENCES fr_customer (id)
);
CREATE INDEX idx_fr_customer_follow_customer ON fr_customer_follow (customer_id, create_time);

-- 参考方案与预算版本：只追加；BUDGET 的尺寸参数是设计委托的依据
CREATE TABLE fr_customer_scheme
(
    id             BIGINT        NOT NULL,
    company_id     BIGINT        NOT NULL,
    customer_id    BIGINT        NOT NULL,
    kind           VARCHAR(16)   NOT NULL,
    version        INT           NOT NULL,
    title          VARCHAR(128)  NOT NULL,
    payload        TEXT          NULL,
    width          NUMERIC(6, 2) NULL,
    depth          NUMERIC(6, 2) NULL,
    floors         INT           NULL,
    area           NUMERIC(8, 2) NULL,
    total_cents    BIGINT        NULL,
    author_user_id BIGINT        NOT NULL,
    create_time    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_customer_scheme PRIMARY KEY (id),
    CONSTRAINT fk_fr_customer_scheme_customer FOREIGN KEY (customer_id) REFERENCES fr_customer (id),
    CONSTRAINT ck_fr_customer_scheme_kind CHECK (kind IN ('BUDGET', 'PLAN', 'FACADE')),
    CONSTRAINT ck_fr_customer_scheme_budget CHECK (kind <> 'BUDGET' OR (width > 0 AND depth > 0 AND floors > 0 AND area > 0)),
    CONSTRAINT uk_fr_customer_scheme_version UNIQUE (customer_id, kind, version)
);
