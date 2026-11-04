-- 回滚 V20261104.822：移交包冻结方案版本列
ALTER TABLE fr_commission_handover DROP COLUMN IF EXISTS frozen_scheme_version;
