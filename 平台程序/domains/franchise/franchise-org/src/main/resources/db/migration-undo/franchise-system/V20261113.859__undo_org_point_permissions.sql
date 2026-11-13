-- V20261113.859 回滚：移除公司积分池岗位权限
DELETE FROM system_role_menu WHERE menu_id IN (710900, 710901, 710902);
DELETE FROM system_menu WHERE id IN (710900, 710901, 710902);
