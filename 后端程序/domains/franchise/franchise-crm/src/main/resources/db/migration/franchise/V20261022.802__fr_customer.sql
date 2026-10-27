-- V20261022.802：加盟协同域 · 客户、客资、跟进（M2.2）。组织范围按 company_id 过滤。

CREATE TABLE fr_customer
(
    id            BIGINT       NOT NULL,
    company_id    BIGINT       NOT NULL,
    team_id       BIGINT       NULL,
    owner_user_id BIGINT       NOT NULL,
    name          VARCHAR(32)  NOT NULL,
    phone         VARCHAR(16)  NOT NULL,
    need          VARCHAR(500) NOT NULL,
    source        VARCHAR(32)  NOT NULL,
    revision      BIGINT       NOT NULL DEFAULT 1,
    create_time   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_time   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_customer PRIMARY KEY (id),
    -- 同一门店同一手机号只有一个客户；再次咨询关联原客户
    CONSTRAINT uk_fr_customer_phone UNIQUE (company_id, phone)
);
CREATE INDEX idx_fr_customer_owner ON fr_customer (company_id, owner_user_id);
CREATE INDEX idx_fr_customer_team ON fr_customer (company_id, team_id);
COMMENT ON TABLE fr_customer IS '门店客户；负责人即登记客资的业务员，责任转交在人员管理中办理';

CREATE TABLE fr_lead
(
    id                BIGINT       NOT NULL,
    company_id        BIGINT       NOT NULL,
    team_id           BIGINT       NULL,
    registrar_user_id BIGINT       NOT NULL,
    registrar_post_id BIGINT       NOT NULL,
    customer_id       BIGINT       NOT NULL,
    link_kind         VARCHAR(20)  NOT NULL,
    name              VARCHAR(32)  NOT NULL,
    phone             VARCHAR(16)  NOT NULL,
    need              VARCHAR(500) NOT NULL,
    source            VARCHAR(32)  NOT NULL,
    create_time       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_lead PRIMARY KEY (id),
    CONSTRAINT ck_fr_lead_link_kind CHECK (link_kind IN ('NEW_CUSTOMER', 'EXISTING_CUSTOMER')),
    CONSTRAINT fk_fr_lead_customer FOREIGN KEY (customer_id) REFERENCES fr_customer (id)
);
CREATE INDEX idx_fr_lead_customer ON fr_lead (customer_id);
COMMENT ON TABLE fr_lead IS '每次咨询登记一条客资；保留登记人、来源和关联方式';

CREATE TABLE fr_follow_up
(
    id              BIGINT       NOT NULL,
    company_id      BIGINT       NOT NULL,
    customer_id     BIGINT       NOT NULL,
    author_user_id  BIGINT       NOT NULL,
    author_post_id  BIGINT       NOT NULL,
    note            VARCHAR(1000) NOT NULL,
    next_visit_date DATE         NULL,
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_follow_up PRIMARY KEY (id),
    CONSTRAINT fk_fr_follow_up_customer FOREIGN KEY (customer_id) REFERENCES fr_customer (id)
);
CREATE INDEX idx_fr_follow_up_customer ON fr_follow_up (customer_id, create_time);
COMMENT ON TABLE fr_follow_up IS '客户跟进记录；只追加';
