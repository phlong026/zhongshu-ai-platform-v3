-- V20261104.707：正式组织开通（M01-T06 / R02）——Web 平台管理员入口菜单与权限码。
-- 权限域：platform:org:onboard 为平台级权限（无 pf 岗位上下文），挂底座超管角色（id=1, super_admin）；
-- 组织内管理端点仍走 @post.has（岗位即权限），两者分属不同权限域。
-- Codex R1 P1-2：页面路由需 type=2 菜单（按钮 type=3 不进菜单树）；父目录 710100 置可见。

UPDATE system_menu SET visible = TRUE WHERE id = 710100;

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, updater)
VALUES (710105, '组织开通', '', 2, 1, 710100, 'onboard', 'ep:plus', 'zs/platform-org/index', 'PlatformOrgOnboard', 0, TRUE, TRUE, TRUE, '1', '1'),
       (710104, '组织开通提交', 'platform:org:onboard', 3, 1, 710105, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1');

INSERT INTO system_role_menu (id, role_id, menu_id, creator, updater, tenant_id)
SELECT 712000 + row_number() OVER (ORDER BY m.id), r.id, m.id, '1', '1', 1
FROM system_role r JOIN system_menu m ON m.id IN (710104, 710105)
WHERE r.id = 1;
