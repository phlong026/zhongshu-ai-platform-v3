-- B 专用空库撤销：先停止应用、恢复任务、运营写入及其他迁移，再按 961 -> 945 -> 925 执行。
-- 使用指向正确 B schema 的独立 owner 连接，READ COMMITTED；不要套入已有事务。
-- 任意命令状态、设计钱包历史或命令审计均禁止撤销；保留事实，改为只回滚应用。
-- 不修改 Flyway 历史，不清理资金或菜单；前置 undo 自行负责各自对象。
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '15s';
DO $$
BEGIN
    -- 高隔离级别的旧快照不会因取得表锁而刷新，必须在读取空表前拒绝。
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'U925_REQUIRES_READ_COMMITTED: use a fresh READ COMMITTED connection';
    END IF;

    LOCK TABLE fr_design_remote_command IN ACCESS EXCLUSIVE MODE;
    IF to_regclass('fr_design_billing') IS NOT NULL
            OR to_regclass('fr_design_price') IS NOT NULL
            OR to_regclass('fr_ops_design_command_page') IS NOT NULL
            OR to_regclass('fr_ops_design_price_versions') IS NOT NULL THEN
        RAISE EXCEPTION 'U925_DEPENDENCIES_REMAIN: undo 961 and 945 before 925';
    END IF;
    IF to_regclass('system_menu') IS NOT NULL THEN
        LOCK TABLE system_menu IN SHARE MODE;
        IF EXISTS (SELECT 1 FROM system_menu WHERE id IN (960021,960022,960023)) THEN
            RAISE EXCEPTION 'U925_DEPENDENCIES_REMAIN: 961 menus remain';
        END IF;
    END IF;
    IF to_regclass('system_role_menu') IS NOT NULL THEN
        LOCK TABLE system_role_menu IN SHARE MODE;
        IF EXISTS (SELECT 1 FROM system_role_menu WHERE menu_id IN (960021,960022,960023)) THEN
            RAISE EXCEPTION 'U925_DEPENDENCIES_REMAIN: 961 role menus remain';
        END IF;
    END IF;

    IF EXISTS (SELECT 1 FROM fr_design_remote_command) THEN
        RAISE EXCEPTION 'U925_COMMAND_FACTS_EXIST: retain every command, including terminal states';
    END IF;
    -- 这些业务关联未必有外键；锁住写入后检查，不能仅依赖 DROP RESTRICT。
    IF to_regclass('pf_wallet_charge') IS NOT NULL THEN
        LOCK TABLE pf_wallet_charge IN SHARE MODE;
        IF EXISTS (SELECT 1 FROM pf_wallet_charge WHERE biz_type = 'DESIGN_CAPABILITY') THEN
            RAISE EXCEPTION 'U925_WALLET_FACTS_EXIST: design charges must be retained';
        END IF;
    END IF;
    IF to_regclass('pf_point_ledger') IS NOT NULL THEN
        LOCK TABLE pf_point_ledger IN SHARE MODE;
        IF EXISTS (SELECT 1 FROM pf_point_ledger WHERE biz_type = 'DESIGN_CAPABILITY') THEN
            RAISE EXCEPTION 'U925_WALLET_FACTS_EXIST: design ledger entries must be retained';
        END IF;
    END IF;
    IF to_regclass('pf_command_event') IS NOT NULL THEN
        LOCK TABLE pf_command_event IN SHARE MODE;
        IF EXISTS (SELECT 1 FROM pf_command_event WHERE aggregate_type = 'fr-design-remote') THEN
            RAISE EXCEPTION 'U925_AUDIT_FACTS_EXIST: design command audit must be retained';
        END IF;
    END IF;
END $$;
-- 只允许移除已证明为空的表及其自有索引、identity sequence；其他依赖会令整个事务失败。
DROP TABLE fr_design_remote_command RESTRICT;
COMMIT;
