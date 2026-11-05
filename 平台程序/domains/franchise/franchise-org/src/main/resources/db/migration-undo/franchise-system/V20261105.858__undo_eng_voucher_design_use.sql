-- V20261105.858 undo：撤回窄码菜单与四岗授权
DELETE FROM system_role_menu WHERE menu_id = 710800 AND id >= 714400 AND id < 714500;
DELETE FROM system_menu WHERE id = 710800 AND permission = 'franchise:asset:bytes';
DELETE FROM flyway_schema_history WHERE version = '20261105.858' AND description LIKE '%eng_voucher%';
