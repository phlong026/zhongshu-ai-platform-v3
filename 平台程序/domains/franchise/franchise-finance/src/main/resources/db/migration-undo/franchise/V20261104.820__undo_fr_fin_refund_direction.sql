-- V20261104.820 undo：移除退款方向扩展（M08-T08 首版）
-- R1 修复：①PostgreSQL 无 ALTER TABLE ... DROP INDEX 语法——改独立 DROP INDEX 语句；
-- ②expense 行 receipt_id 为空，直接 SET NOT NULL 必失败——先显式移除本迁移引入的专属数据。
-- ⚠️ DELETE 语义=移除退款方向扩展及其数据（expense 行离开本扩展后无归属列）；
-- 真实资金事实如需保留，回滚前必须先线下导出。
ALTER TABLE fr_fin_refund DROP CONSTRAINT IF EXISTS ck_fr_fin_refund_source;
DROP INDEX IF EXISTS idx_fr_fin_refund_direction;
DROP INDEX IF EXISTS idx_fr_fin_refund_payment;
-- R1 P2：receipt_id 在 820 中以 ALTER DROP NOT NULL 放宽，未命名约束——此处 IF EXISTS 容忍各环境自动命名差异
ALTER TABLE fr_fin_refund DROP CONSTRAINT IF EXISTS fr_fin_refund_receipt_id_not_null;
DELETE FROM fr_fin_refund WHERE receipt_id IS NULL;
ALTER TABLE fr_fin_refund ALTER COLUMN receipt_id SET NOT NULL;
ALTER TABLE fr_fin_refund DROP COLUMN IF EXISTS payment_id;
ALTER TABLE fr_fin_refund DROP COLUMN IF EXISTS refund_direction;
DELETE FROM flyway_schema_history WHERE version = '20261104.820' AND description LIKE '%fr_fin_refund_direction%';
