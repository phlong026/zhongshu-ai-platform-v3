-- 业务员可创建和维护本人 IP；对象归属由内容服务验证，不授予公司人物的管理权限。
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, updater)
VALUES (719927, '维护本人IP', 'franchise:content:persona:self', 3, 5, 710500, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1');

INSERT INTO system_role_menu (id, role_id, menu_id, creator, updater, tenant_id)
SELECT 719927000 + row_number() OVER (ORDER BY id), id, 719927, '1', '1', tenant_id
FROM system_role WHERE code = 'sales' AND tenant_id = 1;
