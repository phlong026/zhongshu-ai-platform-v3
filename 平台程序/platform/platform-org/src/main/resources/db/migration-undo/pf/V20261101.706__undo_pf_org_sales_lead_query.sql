-- 回滚 V20261101.706：撤销 sales-lead 的「查看本组织成员」授权（仅用于未上线环境或回滚演练）
DELETE FROM system_role_menu
WHERE menu_id = (SELECT id FROM system_menu WHERE permission = 'platform:org:query' LIMIT 1)
  AND role_id = (SELECT id FROM system_role WHERE code = 'sales-lead' AND tenant_id = 1 LIMIT 1)
  AND id = 711010;
