-- V20261104.828：M08-T08/R1 P2——退款来源互斥 CHECK 完整化。
-- 820 原约束允许 client 行携带 payment_id（来源不互斥，统计口径有污染通道）；此处收紧为：
-- client = 仅收款项（receipt_id 非空、payment_id 必空）
-- expense = 仅付款项（payment_id 非空、receipt_id 必空）
-- 方向枚举由本约束隐含限定（满足任一分支即 direction ∈ {client, expense}）。
ALTER TABLE fr_fin_refund DROP CONSTRAINT IF EXISTS ck_fr_fin_refund_source;
ALTER TABLE fr_fin_refund ADD CONSTRAINT ck_fr_fin_refund_source CHECK (
  (refund_direction = 'client' AND receipt_id IS NOT NULL AND payment_id IS NULL) OR
  (refund_direction = 'expense' AND payment_id IS NOT NULL AND receipt_id IS NULL)
);
