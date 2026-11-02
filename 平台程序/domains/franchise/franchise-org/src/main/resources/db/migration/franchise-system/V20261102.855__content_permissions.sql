-- V20261102.855：内容创作获客权限码与岗位矩阵（阶段 1.5，franchise-content 域）
-- 菜单 710500 段，role_menu 714100 段。query/write/persona 三码分离：
-- query=sales/sales-lead/boss/owner（v3 蓝本：创作 sales 岗、growth 门店四岗共用查询面）；
-- write=sales；persona=sales-lead/boss/owner（维护人设，sales 只选用）。对象级校验（组织一致）在控制器/服务层。

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, updater)
VALUES (710500, '内容创作获客', '', 1, 59, 0, '/franchise-content', 'ep:video-camera', NULL, NULL, 0, FALSE, TRUE, TRUE, '1', '1'),
       (710501, '查看内容', 'franchise:content:query', 3, 1, 710500, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (710502, '创作内容', 'franchise:content:write', 3, 2, 710500, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (710503, '维护人设', 'franchise:content:persona', 3, 3, 710500, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1');

INSERT INTO system_role_menu (id, role_id, menu_id, creator, updater, tenant_id)
SELECT 714100 + row_number() OVER (ORDER BY r.id, m.id), r.id, m.id, '1', '1', 1
FROM (VALUES ('sales', 'franchise:content:query'),
             ('sales', 'franchise:content:write'),
             ('sales-lead', 'franchise:content:query'),
             ('sales-lead', 'franchise:content:persona'),
             ('boss', 'franchise:content:query'),
             ('boss', 'franchise:content:persona'),
             ('owner', 'franchise:content:query'),
             ('owner', 'franchise:content:persona')) AS rm(role_code, permission)
JOIN system_role r ON r.code = rm.role_code AND r.tenant_id = 1
JOIN system_menu m ON m.permission = rm.permission;
