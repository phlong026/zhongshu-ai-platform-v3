-- 回滚 V20261102.857：绩效提成经营权限码（先 role_menu 后 menu）
DELETE FROM system_role_menu WHERE menu_id IN (710700, 710701, 710702, 710703, 710704);
DELETE FROM system_menu WHERE id IN (710700, 710701, 710702, 710703, 710704);
