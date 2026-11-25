DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM fr_sale_contract WHERE payment_plan_mode='CUSTOM' OR payment_plan_version>1 OR first_cents=0)
       OR EXISTS (SELECT 1 FROM fr_sale_payment_stage WHERE stage LIKE 'STAGE_%' OR plan_version>1) THEN
        RAISE EXCEPTION '已有自定义付款计划或新版本，禁止回退删除计划与收款关联；请使用兼容版本修复';
    END IF;
END $$;
DROP TABLE fr_sale_payment_plan_version;
ALTER TABLE fr_sale_payment_stage DROP CONSTRAINT ck_fr_sale_stage_paid;
ALTER TABLE fr_sale_payment_stage ADD CONSTRAINT ck_fr_sale_stage_paid CHECK (
    (status IN ('PENDING','OPEN') AND paid_receipt_id IS NULL AND paid_at IS NULL AND pay_fingerprint IS NULL)
    OR (status='PAID' AND paid_receipt_id IS NOT NULL AND paid_at IS NOT NULL AND pay_fingerprint IS NOT NULL)
    OR (status='SETTLED' AND ((paid_receipt_id IS NULL AND paid_at IS NULL AND pay_fingerprint IS NULL)
        OR (paid_receipt_id IS NOT NULL AND paid_at IS NOT NULL AND pay_fingerprint IS NOT NULL))));
ALTER TABLE fr_sale_payment_stage DROP CONSTRAINT ck_fr_sale_stage_metadata;
ALTER TABLE fr_sale_payment_stage DROP COLUMN label, DROP COLUMN sequence, DROP COLUMN ratio_bps,
    DROP COLUMN trigger_type, DROP COLUMN trigger_node_index, DROP COLUMN plan_version;
ALTER TABLE fr_sale_payment_stage DROP CONSTRAINT ck_fr_sale_stage;
ALTER TABLE fr_sale_payment_stage ALTER COLUMN stage TYPE VARCHAR(16);
ALTER TABLE fr_sale_payment_stage ADD CONSTRAINT ck_fr_sale_stage CHECK(stage IN ('first','tail','SIGN_AFTER','BASE_DONE','CAP_DONE'));
ALTER TABLE fr_sale_contract DROP COLUMN payment_plan_version, DROP COLUMN payment_plan_mode;
ALTER TABLE fr_sale_contract DROP CONSTRAINT ck_fr_sale_contract_first;
ALTER TABLE fr_sale_contract ADD CONSTRAINT ck_fr_sale_contract_first
    CHECK(first_cents>0 AND first_cents<=total_cents AND total_cents<=1000000000000);
