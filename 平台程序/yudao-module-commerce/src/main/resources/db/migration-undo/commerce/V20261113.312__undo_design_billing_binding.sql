-- V20261113.312 回滚：移除设计域计费归属绑定
DROP INDEX IF EXISTS idx_dbb_org_time;
DROP TABLE IF EXISTS design_billing_binding;
