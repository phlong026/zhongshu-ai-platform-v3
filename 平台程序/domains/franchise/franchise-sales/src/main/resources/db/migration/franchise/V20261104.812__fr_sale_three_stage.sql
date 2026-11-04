-- V20261104.812：统一建房合同三期总闸（M06-T04）
-- 三期=签订后 SIGN_AFTER / 基础完成后 BASE_DONE / 封顶后 CAP_DONE（金额由合同约定，不设固定比例，
-- 允许 0=未约定）；各期可分笔收款，资金唯一事实=fr_fin_receipt，期↔笔经 fr_sale_stage_receipt 关联。
-- 存量两期核实映射：first→SIGN_AFTER、tail→CAP_DONE、PAID→SETTLED、旧 paid_receipt_id 落分笔关联行
-- （不复制流水）；缺失期行补建 amount=0（未约定留待合同变更确认）。

-- 0) 列宽：stage VARCHAR(8) 装不下 SIGN_AFTER/BASE_DONE（9 字符）
ALTER TABLE fr_sale_payment_stage ALTER COLUMN stage TYPE VARCHAR(16);

-- 1) 约束放宽（先约束后数据：0 元期行 / SETTLED 映射 / 六值 stage）
ALTER TABLE fr_sale_payment_stage DROP CONSTRAINT ck_fr_sale_stage_paid;
ALTER TABLE fr_sale_payment_stage ADD CONSTRAINT ck_fr_sale_stage_paid CHECK (
    (status = 'PENDING' AND paid_receipt_id IS NULL AND paid_at IS NULL AND pay_fingerprint IS NULL) OR
    (status = 'PAID' AND paid_receipt_id IS NOT NULL AND paid_at IS NOT NULL AND pay_fingerprint IS NOT NULL) OR
    (status = 'OPEN' AND paid_receipt_id IS NULL AND paid_at IS NULL AND pay_fingerprint IS NULL) OR
    (status = 'SETTLED' AND (
        (paid_receipt_id IS NOT NULL AND paid_at IS NOT NULL AND pay_fingerprint IS NOT NULL) OR
        (paid_receipt_id IS NULL AND paid_at IS NULL AND pay_fingerprint IS NULL))));
-- SETTLED 双形态说明：存量整期核销（迁移映射）保留 paid_* 列值；新分笔协议的期事实在
-- fr_sale_stage_receipt，paid_* 列不再写入（历史核销留痕列冻结保留）
ALTER TABLE fr_sale_payment_stage DROP CONSTRAINT ck_fr_sale_stage_amount;
ALTER TABLE fr_sale_payment_stage ADD CONSTRAINT ck_fr_sale_stage_amount
    CHECK (amount_cents >= 0 AND amount_cents <= 1000000000000);
ALTER TABLE fr_sale_payment_stage DROP CONSTRAINT ck_fr_sale_stage;
ALTER TABLE fr_sale_payment_stage ADD CONSTRAINT ck_fr_sale_stage
    CHECK (stage IN ('first','tail','SIGN_AFTER','BASE_DONE','CAP_DONE'));
ALTER TABLE fr_sale_payment_stage DROP CONSTRAINT ck_fr_sale_stage_status;
ALTER TABLE fr_sale_payment_stage ADD CONSTRAINT ck_fr_sale_stage_status
    CHECK (status IN ('PENDING','PAID','OPEN','SETTLED'));

-- 2) 存量两期核实映射
UPDATE fr_sale_payment_stage SET stage = 'SIGN_AFTER' WHERE stage = 'first';
UPDATE fr_sale_payment_stage SET stage = 'CAP_DONE'  WHERE stage = 'tail';
UPDATE fr_sale_payment_stage SET status = 'SETTLED'  WHERE status = 'PAID';

-- 3) 补齐缺失期行（amount=0 = 未约定；id 取远离雪花与业务 id 的固定段，行数有限）
INSERT INTO fr_sale_payment_stage (id, company_id, customer_id, contract_id, stage, amount_cents, status, deleted)
SELECT 910000000000 + row_number() OVER (ORDER BY c.id),
       c.company_id, c.customer_id, c.id,
       s.stage, 0, 'PENDING', FALSE
FROM fr_sale_contract c
CROSS JOIN (VALUES ('SIGN_AFTER'), ('BASE_DONE'), ('CAP_DONE')) AS s(stage)
WHERE NOT EXISTS (SELECT 1 FROM fr_sale_payment_stage e
                  WHERE e.contract_id = c.id AND e.stage = s.stage);

-- 4) 分笔关联表：一笔 receipt 只归一期（uk receipt_id）
CREATE TABLE fr_sale_stage_receipt (
    id           BIGINT      NOT NULL,
    company_id   BIGINT      NOT NULL,
    contract_id  BIGINT      NOT NULL,
    stage_id     BIGINT      NOT NULL,
    receipt_id   BIGINT      NOT NULL,
    amount_cents BIGINT      NOT NULL CHECK (amount_cents > 0),
    create_time  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_sale_stage_receipt PRIMARY KEY (id),
    CONSTRAINT fk_ssr_stage FOREIGN KEY (stage_id) REFERENCES fr_sale_payment_stage (id),
    CONSTRAINT fk_ssr_contract FOREIGN KEY (contract_id) REFERENCES fr_sale_contract (id),
    CONSTRAINT uk_fr_sale_stage_receipt UNIQUE (receipt_id)
);
CREATE INDEX idx_fr_sale_stage_receipt_stage ON fr_sale_stage_receipt (stage_id);

-- 5) 旧整期核销 → 分笔关联行（金额取期约定额；核实映射留痕，paid_receipt_id 列保留不删）
INSERT INTO fr_sale_stage_receipt (id, company_id, contract_id, stage_id, receipt_id, amount_cents)
SELECT 920000000000 + row_number() OVER (ORDER BY s.id),
       s.company_id, s.contract_id, s.id, s.paid_receipt_id, s.amount_cents
FROM fr_sale_payment_stage s
WHERE s.paid_receipt_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM fr_sale_stage_receipt r WHERE r.receipt_id = s.paid_receipt_id);
