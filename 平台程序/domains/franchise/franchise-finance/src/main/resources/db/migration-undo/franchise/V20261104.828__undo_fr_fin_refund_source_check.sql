-- V20261104.828 undo：恢复 820 原始（较宽）来源 CHECK
ALTER TABLE fr_fin_refund DROP CONSTRAINT IF EXISTS ck_fr_fin_refund_source;
ALTER TABLE fr_fin_refund ADD CONSTRAINT ck_fr_fin_refund_source CHECK (
  (refund_direction = 'client' AND receipt_id IS NOT NULL) OR
  (refund_direction = 'expense' AND payment_id IS NOT NULL AND receipt_id IS NULL)
);
DELETE FROM flyway_schema_history WHERE version = '20261104.828' AND description LIKE '%fr_fin_refund_source_check%';
