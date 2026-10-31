-- V20261031.703：组织成员管理权限码（管理员指派制）
-- 岗位即角色（system_role.code，由加盟协同域 V851 建立）；本迁移只挂菜单按钮与岗位×权限矩阵。
-- 只在完整库执行（平台 Flyway 路径含本目录；集成测试无 system_menu 也不受影响——roleHasPermission 严格模式视为无权限）。
-- 菜单编号用 710xxx 段（对应平台层 7xx），role_menu 用 711xxx，避开 yudao 与加盟域种子。

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, updater)
VALUES (710100, '平台组织', '', 1, 55, 0, '/platform-org', 'ep:office-building', NULL, NULL, 0, FALSE, TRUE, TRUE, '1', '1'),
       (710101, '查看本组织成员', 'platform:org:query', 3, 1, 710100, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (710102, '管理本组织成员与部门', 'platform:org:manage', 3, 2, 710100, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1');

-- 只有老板与门店负责人可管理成员；范围校验（本组织）在服务内二次把关
INSERT INTO system_role_menu (id, role_id, menu_id, creator, updater, tenant_id)
SELECT 711000 + row_number() OVER (ORDER BY r.id, m.id), r.id, m.id, '1', '1', 1
FROM (VALUES ('boss', 'platform:org:query'),
             ('boss', 'platform:org:manage'),
             ('owner', 'platform:org:query'),
             ('owner', 'platform:org:manage')) AS rm(role_code, permission)
JOIN system_role r ON r.code = rm.role_code AND r.tenant_id = 1
JOIN system_menu m ON m.permission = rm.permission;
