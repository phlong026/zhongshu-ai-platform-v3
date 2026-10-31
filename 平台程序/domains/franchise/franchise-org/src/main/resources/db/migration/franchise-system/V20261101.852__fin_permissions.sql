-- V20261101.852：财务账簿权限码与岗位矩阵（B1）
-- 沿用 851 已建岗位角色；本迁移只挂菜单按钮与岗位×权限矩阵。菜单编号 710200 段（平台组织 710100 之后），role_menu 712000 段。
-- 矩阵：finance/finance-lead=query+register；finance-lead 额外=payment-void+refund-void；boss/owner=query+refund-void（仅退款作废）；工程/业务/设计行政岗无任何 fr-fin 权限。

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, updater)
VALUES (710200, '财务账簿', '', 1, 56, 0, '/franchise-finance', 'ep:money', NULL, NULL, 0, FALSE, TRUE, TRUE, '1', '1'),
       (710201, '查看账簿', 'franchise:fin:query', 3, 1, 710200, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (710202, '登记收付款与退款', 'franchise:fin:register', 3, 2, 710200, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (710203, '作废付款', 'franchise:fin:payment-void', 3, 3, 710200, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (710204, '作废退款', 'franchise:fin:refund-void', 3, 4, 710200, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1');

INSERT INTO system_role_menu (id, role_id, menu_id, creator, updater, tenant_id)
SELECT 712000 + row_number() OVER (ORDER BY r.id, m.id), r.id, m.id, '1', '1', 1
FROM (VALUES ('finance', 'franchise:fin:query'),
             ('finance', 'franchise:fin:register'),
             ('finance-lead', 'franchise:fin:query'),
             ('finance-lead', 'franchise:fin:register'),
             ('finance-lead', 'franchise:fin:payment-void'),
             ('finance-lead', 'franchise:fin:refund-void'),
             ('boss', 'franchise:fin:query'),
             ('boss', 'franchise:fin:refund-void'),
             ('owner', 'franchise:fin:query'),
             ('owner', 'franchise:fin:refund-void')) AS rm(role_code, permission)
JOIN system_role r ON r.code = rm.role_code AND r.tenant_id = 1
JOIN system_menu m ON m.permission = rm.permission;
