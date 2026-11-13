-- V20261113.110 回滚：移除旧设计账号身份映射
DROP INDEX IF EXISTS idx_dim_status;
DROP INDEX IF EXISTS idx_dim_org;
DROP TABLE IF EXISTS design_identity_mapping;
