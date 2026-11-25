-- Contract plans are the receivable source. Engineering stores only the prepared version/snapshot.
ALTER TABLE fr_eng_project ADD COLUMN payment_plan_version integer,
    ADD COLUMN payment_plan_snapshot jsonb;
ALTER TABLE fr_eng_project ADD CONSTRAINT ck_fr_eng_payment_plan_version CHECK(payment_plan_version IS NULL OR payment_plan_version>0);
ALTER TABLE fr_eng_collection_term ALTER COLUMN stage TYPE varchar(64);
ALTER TABLE fr_eng_collection ALTER COLUMN stage TYPE varchar(64);
ALTER TABLE fr_eng_followup ALTER COLUMN stage TYPE varchar(64);
ALTER TABLE fr_eng_collection_term DROP CONSTRAINT ck_fr_eng_collection_term_stage;
ALTER TABLE fr_eng_collection_term ADD CONSTRAINT ck_fr_eng_collection_term_stage CHECK(stage IN('START','TOPPING','SIGN_AFTER','BASE_DONE','CAP_DONE','tail') OR stage ~ '^STAGE_[0-9]+$');
ALTER TABLE fr_eng_collection DROP CONSTRAINT ck_fr_eng_collection_stage;
ALTER TABLE fr_eng_collection ADD CONSTRAINT ck_fr_eng_collection_stage CHECK(stage IN('START','TOPPING','SIGN_AFTER','BASE_DONE','CAP_DONE','tail') OR stage ~ '^STAGE_[0-9]+$');
ALTER TABLE fr_eng_followup DROP CONSTRAINT ck_fr_eng_followup_stage;
ALTER TABLE fr_eng_followup ADD CONSTRAINT ck_fr_eng_followup_stage CHECK(stage IS NULL OR stage IN('START','TOPPING','SIGN_AFTER','BASE_DONE','CAP_DONE','tail') OR stage ~ '^STAGE_[0-9]+$');
ALTER TABLE fr_eng_collection DROP CONSTRAINT ck_fr_eng_collection_purpose;
ALTER TABLE fr_eng_collection ADD CONSTRAINT ck_fr_eng_collection_purpose CHECK(purpose IN('工程预收款','工程尾款','基础完成款','工程进度款'));
