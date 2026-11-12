-- 仅用于隔离/停写环境。存在运营审计或核对记录时拒绝删除，交主线保留数据并前滚。
BEGIN;
LOCK TABLE pf_ops_config_audit,pf_ops_anomaly_review IN ACCESS EXCLUSIVE MODE;
DO $$ BEGIN
    IF EXISTS(SELECT 1 FROM pf_ops_config_audit) OR EXISTS(SELECT 1 FROM pf_ops_anomaly_review) THEN
        RAISE EXCEPTION 'operations audit is not empty; preserve data and use a reviewed forward recovery';
    END IF;
END $$;
DROP TABLE pf_ops_anomaly_review;
DROP TABLE pf_ops_config_audit;
DROP FUNCTION pf_ops_append_only();
DROP INDEX pf_ops_anomaly_tenant_id;
DROP INDEX pf_ops_wallet_page;
DROP INDEX pf_ops_order_page;
DROP INDEX pf_ops_ledger_page;
DROP INDEX pf_ops_anomaly_page;
DELETE FROM system_role_menu WHERE menu_id IN (960000,960001,960002,960003,960004,960005,960006,960011,960012);
DELETE FROM system_menu WHERE id IN (960000,960001,960002,960003,960004,960005,960006,960011,960012);
COMMIT;
