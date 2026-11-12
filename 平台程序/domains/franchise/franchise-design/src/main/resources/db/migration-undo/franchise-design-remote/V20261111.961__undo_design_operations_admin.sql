-- 先撤销 961 菜单，再撤销 960；保留全部价目、命令、计费与审计事实。
BEGIN;
DROP INDEX fr_ops_design_price_versions;
DROP INDEX fr_ops_design_command_page;
DELETE FROM system_role_menu WHERE menu_id IN (960021,960022,960023);
DELETE FROM system_menu WHERE id IN (960021,960022,960023);
COMMIT;
