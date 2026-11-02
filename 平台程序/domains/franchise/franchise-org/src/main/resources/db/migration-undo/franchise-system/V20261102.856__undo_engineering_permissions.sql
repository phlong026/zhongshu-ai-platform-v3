-- 回滚 V20261102.856：工程全周期权限码（先 role_menu 后 menu）
DELETE FROM system_role_menu WHERE menu_id IN (710600, 710601, 710602, 710603, 710604);
DELETE FROM system_menu WHERE id IN (710600, 710601, 710602, 710603, 710604);
