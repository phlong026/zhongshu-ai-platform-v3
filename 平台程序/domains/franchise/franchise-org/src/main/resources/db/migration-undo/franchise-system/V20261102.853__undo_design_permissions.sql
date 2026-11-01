-- 回滚 V20261102.853：设计赋能权限码与岗位矩阵
DELETE FROM system_role_menu WHERE menu_id IN (SELECT id FROM system_menu WHERE id IN (710300, 710301));
DELETE FROM system_menu WHERE id IN (710300, 710301);
