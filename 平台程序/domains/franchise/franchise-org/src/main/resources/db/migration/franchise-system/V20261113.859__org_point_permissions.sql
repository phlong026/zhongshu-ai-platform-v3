-- V20261113.859：公司积分池老板视图岗位权限（24 号批 C 遗留 P2）
-- 菜单 710900 段，role_menu 714600 段：query=老板/owner（看余额/用量/流水/合并视图/额度清单）；
-- write=boss/owner（设额度、设业务权限）。平台侧调点/迁移仍走 yudao 权限码（commerce:org-point:*）。
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, updater)
VALUES (710900, '公司积分池', '', 1, 60, 0, '/franchise-org-point', 'ep:coin', NULL, NULL, 0, FALSE, TRUE, TRUE, '1', '1'),
       (710901, '查看公司积分', 'franchise:org:point:query', 3, 1, 710900, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (710902, '管理公司积分', 'franchise:org:point:write', 3, 2, 710900, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1');

INSERT INTO system_role_menu (id, role_id, menu_id, creator, updater, tenant_id)
SELECT 714600 + row_number() OVER (ORDER BY r.id, m.id), r.id, m.id, '1', '1', 1
FROM (VALUES ('boss', 'franchise:org:point:query'),
             ('boss', 'franchise:org:point:write'),
             ('owner', 'franchise:org:point:query'),
             ('owner', 'franchise:org:point:write')) AS rm(role_code, permission)
JOIN system_role r ON r.code = rm.role_code AND r.tenant_id = 1
JOIN system_menu m ON m.permission = rm.permission;
