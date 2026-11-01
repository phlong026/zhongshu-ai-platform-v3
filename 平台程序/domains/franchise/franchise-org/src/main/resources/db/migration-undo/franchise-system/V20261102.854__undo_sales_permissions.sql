-- 回滚 V20261102.854：业务交易流权限码与岗位矩阵
DELETE FROM system_role_menu WHERE menu_id IN (SELECT id FROM system_menu WHERE id IN (710400, 710401, 710402, 710403));
DELETE FROM system_menu WHERE id IN (710400, 710401, 710402, 710403);
