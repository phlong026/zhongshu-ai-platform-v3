-- V20261104.829：M08-T08——异常资金登记（独立待关联载体，R05 规则承接）。
-- 语义：线下已发生但「原单缺失 / 超原单余额 / 凭据或日期待核」的真实资金事实，留痕不凑平——
-- 本表不进任何有效账口径（有效实收/有效已付/净额只读 fr_fin_receipt/fr_fin_payment/fr_fin_refund）；
-- 不得借本载体放宽正常退款/付款接口上限、伪造收入或改写原单。
-- 日期待核（business_date IS NULL）读侧单列「待归月」（M08-T05 契约承接：不回退 create_time）。
-- 状态：已核待关联=事实已核实待关联原单；已关联=link 指向正常单据（仅标注，不改任何有效账数字）；已作废=误录撤销。
CREATE TABLE fr_fin_abnormal_fund (
    id                    BIGINT       NOT NULL,
    company_id            BIGINT       NOT NULL,
    fund_direction        VARCHAR(16)  NOT NULL CHECK (fund_direction IN ('客户收款','客户退款','支出退回')),
    amount_cents          BIGINT       NOT NULL CHECK (amount_cents > 0 AND amount_cents <= 1000000000000),
    counterparty          VARCHAR(64)  NULL,
    related_project_id    BIGINT       NULL,
    related_commission_id BIGINT       NULL,
    stage_or_category     VARCHAR(32)  NULL,
    business_date         DATE         NULL,
    method                VARCHAR(16)  NULL CHECK (method IS NULL OR method IN ('bank','cash','other')),
    transaction_no        VARCHAR(64)  NULL,
    reason                VARCHAR(255) NULL,
    voucher_file_id       BIGINT       NULL,
    status                VARCHAR(16)  NOT NULL DEFAULT '已核待关联' CHECK (status IN ('已核待关联','已关联','已作废')),
    linked_type           VARCHAR(16)  NULL CHECK (linked_type IS NULL OR linked_type IN ('receipt','refund','payment')),
    linked_id             BIGINT       NULL,
    operator_user_id      BIGINT       NOT NULL,
    void_by_user_id       BIGINT       NULL,
    void_reason           VARCHAR(255) NULL,
    voided_at             TIMESTAMPTZ  NULL,
    tenant_id             BIGINT       NOT NULL DEFAULT 1,
    creator               VARCHAR(64)  NULL,
    create_time           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater               VARCHAR(64)  NULL,
    update_time           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted               BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_fr_fin_abnormal_fund PRIMARY KEY (id)
);
COMMENT ON TABLE fr_fin_abnormal_fund IS 'M08-T08 异常资金登记：线下真实资金事实的独立留痕，不进有效账；日期待核单列待归月';
CREATE INDEX idx_fr_fin_abnormal_fund_org ON fr_fin_abnormal_fund (company_id, status, create_time DESC);
-- 同公司内同流水号防重复登记（部分索引，空号不限）；有意不进 fr_fin_txn_no——不挡后续真实原单登记。
-- R2 P2：已作废行让出号码（异常登记无更正链——作废=误录移除，同号重登视为新事实），未作废行仍防同号重复。
CREATE UNIQUE INDEX uk_fr_fin_abnormal_fund_txn ON fr_fin_abnormal_fund (company_id, transaction_no)
    WHERE transaction_no IS NOT NULL AND status <> '已作废';
-- R3 P1：同一目标单据至多解释一笔异常资金——DB 级兜底并发双关联（服务层 requireNotOccupied 前置给友好错误）
CREATE UNIQUE INDEX uk_fr_fin_abnormal_fund_link ON fr_fin_abnormal_fund (company_id, linked_type, linked_id)
    WHERE status = '已关联' AND linked_type IS NOT NULL AND linked_id IS NOT NULL;
