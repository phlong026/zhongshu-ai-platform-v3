-- V20261104.830：M08-T08/R1 P2——fr_fin_txn_no owner_type 枚举扩展 'expense-refund'。
-- 支出退回（供应商/劳务退回）流水号与收款/付款/客户退款同进交易身份防重表；同号更正链 owner 原子切换同语义。
ALTER TABLE fr_fin_txn_no DROP CONSTRAINT IF EXISTS fr_fin_txn_no_owner_type_check;
ALTER TABLE fr_fin_txn_no ADD CONSTRAINT fr_fin_txn_no_owner_type_check
    CHECK (owner_type IN ('receipt','payment','refund','expense-refund'));
