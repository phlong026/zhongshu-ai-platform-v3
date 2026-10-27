-- V20261022.851：加盟协同域的 yudao 角色、菜单与权限码（M2.2）
-- 岗位即角色（system_role.code = pf_post_template.role_code），按钮权限码供 @post.has 校验。
-- 只在完整库执行（本目录不在平台集成测试的 Flyway 路径中）；编号用 8100xx 段，避开 yudao 种子。
-- 租户 1 为系统租户（套餐 0，拥有全部菜单）。菜单暂不显示：后台页面在 M5 加入时再改为可见。

INSERT INTO system_role (id, name, code, sort, data_scope, data_scope_dept_ids, status, type, remark, creator, updater, tenant_id)
VALUES (810001, '业务员', 'sales', 101, 1, '', 0, 1, '加盟协同域岗位', '1', '1', 1),
       (810002, '业务负责人', 'sales-lead', 102, 1, '', 0, 1, '加盟协同域岗位', '1', '1', 1),
       (810003, '工程人员', 'engineer', 103, 1, '', 0, 1, '加盟协同域岗位', '1', '1', 1),
       (810004, '工程负责人', 'project-manager', 104, 1, '', 0, 1, '加盟协同域岗位', '1', '1', 1),
       (810005, '财务专员', 'finance', 105, 1, '', 0, 1, '加盟协同域岗位', '1', '1', 1),
       (810006, '财务负责人', 'finance-lead', 106, 1, '', 0, 1, '加盟协同域岗位', '1', '1', 1),
       (810007, '老板', 'boss', 107, 1, '', 0, 1, '加盟协同域岗位', '1', '1', 1),
       (810008, '门店负责人', 'owner', 108, 1, '', 0, 1, '加盟协同域岗位', '1', '1', 1),
       (810009, '设计师', 'hq-designer', 109, 1, '', 0, 1, '加盟协同域岗位', '1', '1', 1),
       (810010, '设计负责人', 'hq-design-lead', 110, 1, '', 0, 1, '加盟协同域岗位', '1', '1', 1),
       (810011, '设计行政', 'hq-design-admin', 111, 1, '', 0, 1, '加盟协同域岗位', '1', '1', 1),
       (810012, '运营专员', 'hq-operations', 112, 1, '', 0, 1, '加盟协同域岗位', '1', '1', 1),
       (810013, '运营负责人', 'hq-operations-lead', 113, 1, '', 0, 1, '加盟协同域岗位', '1', '1', 1);

-- 一级菜单（目录）与按钮权限
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, updater)
VALUES (810100, '加盟协同', '', 1, 60, 0, '/franchise', 'ep:shop', NULL, NULL, 0, FALSE, TRUE, TRUE, '1', '1'),
       (810101, '登记客资', 'franchise:lead:capture', 3, 1, 810100, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (810102, '处理待关联客资', 'franchise:lead:handle', 3, 2, 810100, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (810103, '查看客户', 'franchise:customer:query', 3, 3, 810100, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (810104, '办理客户', 'franchise:customer:write', 3, 4, 810100, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (810105, '客户责任转交', 'franchise:customer:transfer', 3, 5, 810100, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (810106, '标准设计费用', 'franchise:design-policy:manage', 3, 6, 810100, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (810107, '查看设计委托', 'franchise:commission:query', 3, 7, 810100, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (810108, '提交设计委托与移交', 'franchise:commission:submit', 3, 8, 810100, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (810109, '审批委托费用', 'franchise:commission:fee-decide', 3, 9, 810100, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (810110, '设计完成与成果下发', 'franchise:commission:design', 3, 10, 810100, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1'),
       (810111, '核查接收移交资料', 'franchise:commission:receive', 3, 11, 810100, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1');

-- 岗位 × 权限矩阵（与 M2 设计 §4.2 一致，由 FranchiseSystemSeedTest 锁定）
INSERT INTO system_role_menu (id, role_id, menu_id, creator, updater, tenant_id)
SELECT 811000 + row_number() OVER (ORDER BY r.id, m.id), r.id, m.id, '1', '1', 1
FROM (VALUES ('sales', 'franchise:lead:capture'),
             ('sales-lead', 'franchise:lead:capture'),
             ('sales', 'franchise:lead:handle'),
             ('sales-lead', 'franchise:lead:handle'),
             ('boss', 'franchise:lead:handle'),
             ('owner', 'franchise:lead:handle'),
             ('sales', 'franchise:customer:query'),
             ('sales-lead', 'franchise:customer:query'),
             ('boss', 'franchise:customer:query'),
             ('owner', 'franchise:customer:query'),
             ('sales', 'franchise:customer:write'),
             ('sales-lead', 'franchise:customer:write'),
             ('sales-lead', 'franchise:customer:transfer'),
             ('boss', 'franchise:customer:transfer'),
             ('owner', 'franchise:customer:transfer'),
             ('boss', 'franchise:design-policy:manage'),
             ('sales', 'franchise:commission:query'),
             ('sales-lead', 'franchise:commission:query'),
             ('boss', 'franchise:commission:query'),
             ('owner', 'franchise:commission:query'),
             ('hq-design-admin', 'franchise:commission:query'),
             ('engineer', 'franchise:commission:query'),
             ('project-manager', 'franchise:commission:query'),
             ('sales', 'franchise:commission:submit'),
             ('sales-lead', 'franchise:commission:submit'),
             ('boss', 'franchise:commission:fee-decide'),
             ('hq-design-admin', 'franchise:commission:design'),
             ('engineer', 'franchise:commission:receive'),
             ('project-manager', 'franchise:commission:receive')) AS g (role_code, permission)
         JOIN system_role r ON r.code = g.role_code AND r.tenant_id = 1 AND r.id BETWEEN 810001 AND 810013
         JOIN system_menu m ON m.permission = g.permission AND m.id BETWEEN 810101 AND 810199;

-- 拥有任一按钮的岗位同时拥有一级菜单
INSERT INTO system_role_menu (id, role_id, menu_id, creator, updater, tenant_id)
SELECT 811900 + row_number() OVER (ORDER BY role_id), role_id, 810100, '1', '1', 1
FROM (SELECT DISTINCT role_id FROM system_role_menu WHERE menu_id BETWEEN 810101 AND 810199 AND tenant_id = 1) AS granted;
