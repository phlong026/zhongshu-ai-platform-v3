-- V20261102.857：绩效提成经营权限码与岗位矩阵（阶段 3，franchise-performance 域）
-- 菜单 710700 段，role_menu 714300 段。四码（19 §六 D-9）：
-- query=全员（任务本人/团队范围）；manage=boss/owner（派发/改派/归档/目标/复核/申诉裁决）；
-- commission=boss/owner/finance/finance-lead（owner 可看不可改——服务层：policy·reconcile 仅 boss、release 仅 finance 族）；
-- operate=boss/owner/finance/finance-lead（经营视图/快照/月末对账）。

INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, updater)
VALUES (710700, '绩效提成经营', '', 1, 70, 0, '/franchise-performance', 'ep:data-analysis', NULL, NULL, 0, FALSE, TRUE, TRUE, '1', '1'),
       (710701, '查看绩效', 'franchise:performance:query', 3, 1, 710700, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (710702, '管理绩效', 'franchise:performance:manage', 3, 2, 710700, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (710703, '回款提成', 'franchise:performance:commission', 3, 3, 710700, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (710704, '经营视图', 'franchise:performance:operate', 3, 4, 710700, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1');

INSERT INTO system_role_menu (id, role_id, menu_id, creator, updater, tenant_id)
SELECT 714300 + row_number() OVER (ORDER BY r.id, m.id), r.id, m.id, '1', '1', 1
FROM (VALUES ('sales', 'franchise:performance:query'),
             ('sales-lead', 'franchise:performance:query'),
             ('engineer', 'franchise:performance:query'),
             ('project-manager', 'franchise:performance:query'),
             ('finance', 'franchise:performance:query'),
             ('finance-lead', 'franchise:performance:query'),
             ('hq-design-admin', 'franchise:performance:query'),
             ('boss', 'franchise:performance:query'),
             ('boss', 'franchise:performance:manage'),
             ('boss', 'franchise:performance:commission'),
             ('boss', 'franchise:performance:operate'),
             ('owner', 'franchise:performance:query'),
             ('owner', 'franchise:performance:manage'),
             ('owner', 'franchise:performance:commission'),
             ('owner', 'franchise:performance:operate'),
             ('finance', 'franchise:performance:commission'),
             ('finance-lead', 'franchise:performance:commission'),
             ('finance', 'franchise:performance:operate'),
             ('finance-lead', 'franchise:performance:operate')) AS rm(role_code, permission)
JOIN system_role r ON r.code = rm.role_code AND r.tenant_id = 1
JOIN system_menu m ON m.permission = rm.permission;
