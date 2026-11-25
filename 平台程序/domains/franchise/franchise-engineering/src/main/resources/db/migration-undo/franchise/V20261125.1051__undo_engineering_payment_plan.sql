-- Refuse to discard inherited evidence or custom-stage history. Export/restore belongs to R21.
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM fr_eng_project WHERE payment_plan_version IS NOT NULL)
 OR EXISTS(SELECT 1 FROM fr_eng_collection_term WHERE stage='tail' OR stage LIKE 'STAGE_%')
 OR EXISTS(SELECT 1 FROM fr_eng_collection WHERE stage='tail' OR stage LIKE 'STAGE_%')
 OR EXISTS(SELECT 1 FROM fr_eng_followup WHERE stage='tail' OR stage LIKE 'STAGE_%') THEN
 RAISE EXCEPTION 'Payment plans are in use; preserve a verified backup and reconcile before rollback';
 END IF;
END $$;
ALTER TABLE fr_eng_project DROP COLUMN payment_plan_version,DROP COLUMN payment_plan_snapshot;
ALTER TABLE fr_eng_collection_term DROP CONSTRAINT ck_fr_eng_collection_term_stage;
ALTER TABLE fr_eng_collection_term ADD CONSTRAINT ck_fr_eng_collection_term_stage CHECK(stage IN('START','TOPPING','SIGN_AFTER','BASE_DONE','CAP_DONE'));
ALTER TABLE fr_eng_collection DROP CONSTRAINT ck_fr_eng_collection_stage;
ALTER TABLE fr_eng_collection ADD CONSTRAINT ck_fr_eng_collection_stage CHECK(stage IN('START','TOPPING','SIGN_AFTER','BASE_DONE','CAP_DONE'));
ALTER TABLE fr_eng_followup DROP CONSTRAINT ck_fr_eng_followup_stage;
ALTER TABLE fr_eng_followup ADD CONSTRAINT ck_fr_eng_followup_stage CHECK(stage IS NULL OR stage IN('START','TOPPING','SIGN_AFTER','BASE_DONE','CAP_DONE'));
ALTER TABLE fr_eng_collection DROP CONSTRAINT ck_fr_eng_collection_purpose;
ALTER TABLE fr_eng_collection ADD CONSTRAINT ck_fr_eng_collection_purpose CHECK(purpose IN('工程预收款','工程尾款','基础完成款'));
