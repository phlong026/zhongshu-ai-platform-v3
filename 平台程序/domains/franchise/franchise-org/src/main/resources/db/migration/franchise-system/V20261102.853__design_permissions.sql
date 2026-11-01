-- V20261102.853：设计赋能权限码与岗位矩阵（B2 板块 A / A8）
-- 沿用 851 已建岗位角色；菜单编号 710300 段（852 的 710200 段之后），role_menu 713000 段。
-- 能力门与对象权限分离（S1）：franchise:design:use 仅是调用设计能力的岗位门，
-- 回写/桥接查询另受 CRM CustomerAccess 对象权限约束。
-- 矩阵：sales/sales-lead/hq-design-admin=use；其余 10 岗无任何 fr-design 权限（AC2 全岗位遍历）。

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, updater)
VALUES (710300, '设计赋能', '', 1, 57, 0, '/franchise-design', 'ep:magic-stick', NULL, NULL, 0, FALSE, TRUE, TRUE, '1', '1'),
       (710301, '使用设计赋能', 'franchise:design:use', 3, 1, 710300, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1');

INSERT INTO system_role_menu (id, role_id, menu_id, creator, updater, tenant_id)
SELECT 713000 + row_number() OVER (ORDER BY r.id, m.id), r.id, m.id, '1', '1', 1
FROM (VALUES ('sales', 'franchise:design:use'),
             ('sales-lead', 'franchise:design:use'),
             ('hq-design-admin', 'franchise:design:use')) AS rm(role_code, permission)
JOIN system_role r ON r.code = rm.role_code AND r.tenant_id = 1
JOIN system_menu m ON m.permission = rm.permission;
