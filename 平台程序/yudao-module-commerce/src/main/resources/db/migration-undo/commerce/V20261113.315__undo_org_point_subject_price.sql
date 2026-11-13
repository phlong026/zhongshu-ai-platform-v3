-- V20261113.315 回滚：科目价格表（无 ACTIVE 行时直接删；有配置则数据可丢弃——价格是配置非资金流水）
DROP INDEX IF EXISTS idx_ops_price_subject;
DROP INDEX IF EXISTS uk_ops_price_active;
DROP TABLE IF EXISTS org_point_subject_price;
