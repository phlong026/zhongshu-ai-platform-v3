-- V20261104.830 undo：恢复 fr_fin_txn_no owner_type 三枚举
-- ⚠️ 若存在 owner_type='expense-refund' 的行，收紧 CHECK 前须先处置（undo 820 已清理对应退款行）。
DELETE FROM fr_fin_txn_no WHERE owner_type = 'expense-refund';
ALTER TABLE fr_fin_txn_no DROP CONSTRAINT IF EXISTS fr_fin_txn_no_owner_type_check;
ALTER TABLE fr_fin_txn_no ADD CONSTRAINT fr_fin_txn_no_owner_type_check
    CHECK (owner_type IN ('receipt','payment','refund'));
DELETE FROM flyway_schema_history WHERE version = '20261104.830' AND description LIKE '%fr_fin_txn_no_owner_types%';
