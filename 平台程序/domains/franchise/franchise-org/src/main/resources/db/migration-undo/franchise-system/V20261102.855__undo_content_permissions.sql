-- 回滚 V20261102.855：内容创作获客权限码（先 role_menu 后 menu）
DELETE FROM system_role_menu WHERE menu_id IN (710500, 710501, 710502, 710503);
DELETE FROM system_menu WHERE id IN (710500, 710501, 710502, 710503);
