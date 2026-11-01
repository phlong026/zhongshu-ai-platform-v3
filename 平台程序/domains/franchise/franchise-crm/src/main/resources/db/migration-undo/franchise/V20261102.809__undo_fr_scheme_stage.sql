-- 回滚 V20261102.809：fr_customer_scheme 方案升级列
ALTER TABLE fr_customer_scheme DROP CONSTRAINT IF EXISTS ck_fr_customer_scheme_stage;
ALTER TABLE fr_customer_scheme DROP COLUMN IF EXISTS stage;
