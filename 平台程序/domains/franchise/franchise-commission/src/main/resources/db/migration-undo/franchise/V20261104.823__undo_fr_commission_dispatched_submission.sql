-- 回滚 V20261104.823：下发冻结提交版本引用列
ALTER TABLE fr_commission DROP COLUMN IF EXISTS dispatched_submission_version;
