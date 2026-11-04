ALTER TABLE fr_fin_refund DROP CONSTRAINT IF EXISTS ck_fr_fin_refund_source;
ALTER TABLE fr_fin_refund DROP INDEX IF EXISTS idx_fr_fin_refund_direction;
ALTER TABLE fr_fin_refund DROP INDEX IF EXISTS idx_fr_fin_refund_payment;
ALTER TABLE fr_fin_refund DROP CONSTRAINT IF EXISTS fr_fin_refund_receipt_id_not_null;
ALTER TABLE fr_fin_refund ALTER COLUMN receipt_id SET NOT NULL;
ALTER TABLE fr_fin_refund DROP COLUMN IF EXISTS payment_id;
ALTER TABLE fr_fin_refund DROP COLUMN IF EXISTS refund_direction;
DELETE FROM flyway_schema_history WHERE version = '20261104.820' AND description LIKE '%fr_fin_refund_direction%';
