-- 回滚 V20261102.807：fr_customer_scheme 设计项目桥接列（B2 板块 A / A7）
DROP INDEX IF EXISTS idx_fr_customer_scheme_design_project;
ALTER TABLE fr_customer_scheme DROP COLUMN IF EXISTS design_project_id;
