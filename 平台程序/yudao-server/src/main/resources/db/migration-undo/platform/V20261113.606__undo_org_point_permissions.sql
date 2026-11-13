-- V20261113.606 回滚：移除公司积分池平台管理权限
DELETE FROM system_role_menu WHERE menu_id IN (9570, 9571);
DELETE FROM system_menu WHERE id IN (9570, 9571);
