-- V20261102.856：工程全周期权限码与岗位矩阵（阶段 2，franchise-engineering 域）
-- 菜单 710600 段，role_menu 714200 段。query/write/terms/collect 四码分离（17 §六）：
-- query=engineer/project-manager/boss/owner/finance/finance-lead；
-- write=engineer/project-manager（服务层另校验 owner=负责人/指定工程师本人，D-14）；
-- terms=finance/finance-lead/boss/owner（采购金额仅财务线与老板）；
-- collect=finance/finance-lead（收款仅门店财务；boss 代登记不做，待决①）。

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, updater)
VALUES (710600, '工程全周期', '', 1, 60, 0, '/franchise-engineering', 'ep:office-building', NULL, NULL, 0, FALSE, TRUE, TRUE, '1', '1'),
       (710601, '查看工程', 'franchise:engineering:query', 3, 1, 710600, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (710602, '记录工程', 'franchise:engineering:write', 3, 2, 710600, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (710603, '材料采购金额', 'franchise:engineering:terms', 3, 3, 710600, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (710604, '工程收款登记', 'franchise:engineering:collect', 3, 4, 710600, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1');

INSERT INTO system_role_menu (id, role_id, menu_id, creator, updater, tenant_id)
SELECT 714200 + row_number() OVER (ORDER BY r.id, m.id), r.id, m.id, '1', '1', 1
FROM (VALUES ('engineer', 'franchise:engineering:query'),
             ('engineer', 'franchise:engineering:write'),
             ('project-manager', 'franchise:engineering:query'),
             ('project-manager', 'franchise:engineering:write'),
             ('boss', 'franchise:engineering:query'),
             ('boss', 'franchise:engineering:terms'),
             ('owner', 'franchise:engineering:query'),
             ('owner', 'franchise:engineering:terms'),
             ('finance', 'franchise:engineering:query'),
             ('finance', 'franchise:engineering:terms'),
             ('finance', 'franchise:engineering:collect'),
             ('finance-lead', 'franchise:engineering:query'),
             ('finance-lead', 'franchise:engineering:terms'),
             ('finance-lead', 'franchise:engineering:collect')) AS rm(role_code, permission)
JOIN system_role r ON r.code = rm.role_code AND r.tenant_id = 1
JOIN system_menu m ON m.permission = rm.permission;
