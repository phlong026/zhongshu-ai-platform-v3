-- V20261102.810：business 来源收款身份唯一（B2 板块 B 回款写通）
-- 并发重复入账由 DB 兜底；来源重放由 BusinessReceiptAdapter 按 source_ref 查重（ON CONFLICT 目标限定本索引）。

CREATE UNIQUE INDEX uk_fr_fin_receipt_business ON fr_fin_receipt (source_ref)
    WHERE source_type = 'business' AND deleted = FALSE;
