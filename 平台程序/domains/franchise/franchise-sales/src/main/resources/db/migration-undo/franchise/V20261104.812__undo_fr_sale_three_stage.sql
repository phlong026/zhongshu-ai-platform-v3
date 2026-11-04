-- 回滚 V20261104.812：统一建房合同三期总闸
-- 注意：回滚会丢失分笔粒度（仅保留每期首笔关联回 paid_receipt_id）；三期行 BASE_DONE 删除；
-- SIGN_AFTER/CAP_DONE 映射回 first/tail 语义（旧数据一致性以回滚前备份为准）。
DROP TABLE IF EXISTS fr_sale_stage_receipt;
-- R1 P1：雪花 id（19 位）恒大于 9.1e11 固定段——必须用精确区间限定迁移补建行，否则误删全部正常期次；
-- 迁移补建行基数=合同数×缺失期数（row_number 上限 1e6 内），区间右界留足余量
DELETE FROM fr_sale_payment_stage WHERE stage = 'BASE_DONE'
    OR (id >= 910000000000 AND id < 910100000000);
UPDATE fr_sale_payment_stage SET status = 'PENDING' WHERE status = 'OPEN';
-- 分笔态 SETTLED（无 paid_*）无单笔回指：置回 PENDING（undo 不伪造单笔核销）
UPDATE fr_sale_payment_stage SET status = 'PAID' WHERE status = 'SETTLED' AND paid_receipt_id IS NOT NULL;
UPDATE fr_sale_payment_stage SET status = 'PENDING' WHERE status = 'SETTLED';
UPDATE fr_sale_payment_stage SET stage = 'first' WHERE stage = 'SIGN_AFTER';
UPDATE fr_sale_payment_stage SET stage = 'tail' WHERE stage = 'CAP_DONE';
ALTER TABLE fr_sale_payment_stage DROP CONSTRAINT ck_fr_sale_stage_status;
ALTER TABLE fr_sale_payment_stage ADD CONSTRAINT ck_fr_sale_stage_status CHECK (status IN ('PENDING','PAID'));
ALTER TABLE fr_sale_payment_stage DROP CONSTRAINT ck_fr_sale_stage;
ALTER TABLE fr_sale_payment_stage ADD CONSTRAINT ck_fr_sale_stage CHECK (stage IN ('first','tail'));
ALTER TABLE fr_sale_payment_stage DROP CONSTRAINT ck_fr_sale_stage_amount;
ALTER TABLE fr_sale_payment_stage ADD CONSTRAINT ck_fr_sale_stage_amount CHECK (amount_cents > 0 AND amount_cents <= 1000000000000);
ALTER TABLE fr_sale_payment_stage DROP CONSTRAINT ck_fr_sale_stage_paid;
ALTER TABLE fr_sale_payment_stage ADD CONSTRAINT ck_fr_sale_stage_paid CHECK (
    (status = 'PENDING' AND paid_receipt_id IS NULL AND paid_at IS NULL AND pay_fingerprint IS NULL) OR
    (status = 'PAID' AND paid_receipt_id IS NOT NULL AND paid_at IS NOT NULL AND pay_fingerprint IS NOT NULL));
