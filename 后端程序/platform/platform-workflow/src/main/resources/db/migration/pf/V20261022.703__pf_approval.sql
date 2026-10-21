-- V20261022.703：平台审批中心（M1.4）
-- 一个审批事项（domain + subject_type + subject_id）同一时刻只有一条有效审批（PENDING 或 APPROVED）；
-- 关键内容（signature）变化时旧审批置为 SUPERSEDED，需重新审批。单租户平台表，不带 tenant_id。

CREATE TABLE pf_approval
(
    id                 BIGINT       NOT NULL,
    domain             VARCHAR(32)  NOT NULL,
    org_id             BIGINT       NOT NULL,
    subject_type       VARCHAR(64)  NOT NULL,
    subject_id         BIGINT       NOT NULL,
    subject_version    BIGINT       NULL,
    signature          VARCHAR(128) NOT NULL,
    amount_cents       BIGINT       NULL,
    status             VARCHAR(16)  NOT NULL,
    approver_role_code VARCHAR(64)  NOT NULL,
    submitter_user_id  BIGINT       NOT NULL,
    submitter_post_id  BIGINT       NOT NULL,
    approver_user_id   BIGINT       NULL,
    approver_post_id   BIGINT       NULL,
    note               VARCHAR(500) NULL,
    create_time        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_at         TIMESTAMPTZ  NULL,
    CONSTRAINT pk_pf_approval PRIMARY KEY (id),
    CONSTRAINT ck_pf_approval_status CHECK (status IN ('PENDING', 'APPROVED', 'RETURNED', 'SUPERSEDED')),
    CONSTRAINT ck_pf_approval_amount CHECK (amount_cents IS NULL OR amount_cents >= 0),
    -- 已批准/已退回必须有审批时间，待审批不能有；已作废保留作废前的原样（可能是已批准后被新内容作废）
    CONSTRAINT ck_pf_approval_decided CHECK (
        (status IN ('APPROVED', 'RETURNED') AND decided_at IS NOT NULL)
            OR (status = 'PENDING' AND decided_at IS NULL)
            OR status = 'SUPERSEDED'),
    CONSTRAINT ck_pf_approval_not_self CHECK (approver_user_id IS NULL OR approver_user_id <> submitter_user_id)
);

-- 同一事项只能有一条有效审批
CREATE UNIQUE INDEX uk_pf_approval_live ON pf_approval (domain, subject_type, subject_id)
    WHERE status IN ('PENDING', 'APPROVED');
CREATE INDEX idx_pf_approval_pending ON pf_approval (org_id, approver_role_code) WHERE status = 'PENDING';
CREATE INDEX idx_pf_approval_subject ON pf_approval (domain, subject_type, subject_id, create_time);

COMMENT ON TABLE pf_approval IS '平台审批中心：金额等需审批事项；内容变化即作废，不能自批';
COMMENT ON COLUMN pf_approval.signature IS '业务域对金额、版本、依据等关键内容计算的摘要；不同即需重新审批';
