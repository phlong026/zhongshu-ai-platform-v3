-- V20261104.820：M08-T08 退款方向扩展——支出退回（供应商/劳务退回冲减已付现金）
-- 方向枚举：client=向甲方退建房款（既有行为）；expense=供应商/劳务方退回材料/劳务款（冲减已付现金）
-- expense 退回引用 fr_fin_payment（原付款单），非 fr_fin_receipt

ALTER TABLE fr_fin_refund ADD COLUMN refund_direction VARCHAR(16) NOT NULL DEFAULT 'client';
ALTER TABLE fr_fin_refund ADD COLUMN payment_id BIGINT NULL;

-- client 退回须 receipt_id 非空；expense 退回须 payment_id 非空（receipt_id 置 NULL）
-- 放宽既有 NOT NULL：receipt_id 在 expense 退回时为空
ALTER TABLE fr_fin_refund ALTER COLUMN receipt_id DROP NOT NULL;
ALTER TABLE fr_fin_refund ADD CONSTRAINT ck_fr_fin_refund_source CHECK (
  (refund_direction = 'client' AND receipt_id IS NOT NULL) OR
  (refund_direction = 'expense' AND payment_id IS NOT NULL AND receipt_id IS NULL)
);

CREATE INDEX idx_fr_fin_refund_payment ON fr_fin_refund (payment_id) WHERE payment_id IS NOT NULL;
CREATE INDEX idx_fr_fin_refund_direction ON fr_fin_refund (company_id, refund_direction);
