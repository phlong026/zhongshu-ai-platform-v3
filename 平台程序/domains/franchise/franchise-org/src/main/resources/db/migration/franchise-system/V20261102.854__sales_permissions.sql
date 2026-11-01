-- V20261102.854：业务交易流权限码与岗位矩阵（B2 板块 B / B10）
-- 菜单 710400 段，role_menu 714000 段。query/write/collect 三码分离（collect 与 write 分离）：
-- query=sales/sales-lead/boss/owner/finance/finance-lead；write=sales/sales-lead/boss（owner 只读）；
-- collect=sales/sales-lead/boss/finance/finance-lead。对象级校验（组织一致）在 SalesAccess。

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, updater)
VALUES (710400, '业务交易流', '', 1, 58, 0, '/franchise-sales', 'ep:goods', NULL, NULL, 0, FALSE, TRUE, TRUE, '1', '1'),
       (710401, '查看交易流', 'franchise:sale:query', 3, 1, 710400, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (710402, '办理交易流', 'franchise:sale:write', 3, 2, 710400, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (710403, '办理回款', 'franchise:sale:collect', 3, 3, 710400, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1');

INSERT INTO system_role_menu (id, role_id, menu_id, creator, updater, tenant_id)
SELECT 714000 + row_number() OVER (ORDER BY r.id, m.id), r.id, m.id, '1', '1', 1
FROM (VALUES ('sales', 'franchise:sale:query'),
             ('sales', 'franchise:sale:write'),
             ('sales', 'franchise:sale:collect'),
             ('sales-lead', 'franchise:sale:query'),
             ('sales-lead', 'franchise:sale:write'),
             ('sales-lead', 'franchise:sale:collect'),
             ('boss', 'franchise:sale:query'),
             ('boss', 'franchise:sale:write'),
             ('boss', 'franchise:sale:collect'),
             ('owner', 'franchise:sale:query'),
             ('finance', 'franchise:sale:query'),
             ('finance', 'franchise:sale:collect'),
             ('finance-lead', 'franchise:sale:query'),
             ('finance-lead', 'franchise:sale:collect')) AS rm(role_code, permission)
JOIN system_role r ON r.code = rm.role_code AND r.tenant_id = 1
JOIN system_menu m ON m.permission = rm.permission;
