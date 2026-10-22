-- V20261022.801：加盟协同域 · 组织类型、岗位模板、角色与权限菜单（M2.1）
-- 平台表（pf_）直接写入；yudao 系统表（system_role / system_menu / system_role_menu）用 to_regclass 守卫，
-- 只建平台表的测试库中跳过。角色、菜单编号使用 98xx 段（yudao 序列从 5986 起，众墅原菜单在 95xx）。

INSERT INTO pf_org_type (code, domain, name) VALUES
    ('headquarters', 'franchise', '总部'),
    ('store', 'franchise', '加盟门店');

INSERT INTO pf_post_template (role_code, domain, org_types, line, lead, inherits_role_code) VALUES
    ('fr_sales',           'franchise', 'store',              'sales',       FALSE, NULL),
    ('fr_sales_lead',      'franchise', 'store',              'sales',       TRUE,  'fr_sales'),
    ('fr_engineer',        'franchise', 'store',              'engineering', FALSE, NULL),
    ('fr_project_manager', 'franchise', 'store',              'engineering', TRUE,  'fr_engineer'),
    ('fr_finance',         'franchise', 'headquarters,store', 'finance',     FALSE, NULL),
    ('fr_finance_lead',    'franchise', 'headquarters,store', 'finance',     TRUE,  'fr_finance'),
    ('fr_boss',            'franchise', 'headquarters,store', NULL,          FALSE, NULL),
    ('fr_owner',           'franchise', 'store',              NULL,          FALSE, NULL),
    ('fr_hq_design_admin', 'franchise', 'headquarters',       'design',      FALSE, NULL);

DO $$
BEGIN
    IF to_regclass('system_role') IS NULL OR to_regclass('system_menu') IS NULL OR to_regclass('system_role_menu') IS NULL THEN
        RAISE NOTICE '未发现 yudao 系统表，跳过加盟协同域角色与菜单写入';
        RETURN;
    END IF;

    INSERT INTO system_role (id, name, code, sort, data_scope, data_scope_dept_ids, status, type, remark, creator, deleted, tenant_id) VALUES
        (9801, '业务员',     'fr_sales',           101, 1, '', 0, 2, '加盟协同域岗位', 'migration', 0, 1),
        (9802, '业务负责人', 'fr_sales_lead',      102, 1, '', 0, 2, '加盟协同域岗位', 'migration', 0, 1),
        (9803, '工程人员',   'fr_engineer',        103, 1, '', 0, 2, '加盟协同域岗位', 'migration', 0, 1),
        (9804, '工程负责人', 'fr_project_manager', 104, 1, '', 0, 2, '加盟协同域岗位', 'migration', 0, 1),
        (9805, '财务专员',   'fr_finance',         105, 1, '', 0, 2, '加盟协同域岗位', 'migration', 0, 1),
        (9806, '财务负责人', 'fr_finance_lead',    106, 1, '', 0, 2, '加盟协同域岗位', 'migration', 0, 1),
        (9807, '老板',       'fr_boss',            107, 1, '', 0, 2, '加盟协同域岗位', 'migration', 0, 1),
        (9808, '门店负责人', 'fr_owner',           108, 1, '', 0, 2, '加盟协同域岗位', 'migration', 0, 1),
        (9809, '设计行政',   'fr_hq_design_admin', 109, 1, '', 0, 2, '加盟协同域岗位', 'migration', 0, 1);

    INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, deleted) VALUES
        (9800, '加盟协同', '', 1, 90, 0, '/franchise', 'ep:shop', NULL, NULL, 0, TRUE, TRUE, TRUE, 'migration', 0),
        (9790, '员工微信绑定审计', 'platform:staff-wechat:binding-query', 3, 0, 9800, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, 'migration', 0),
        (9801, '客户查询',     'franchise:customer:query',           3, 1,  9800, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, 'migration', 0),
        (9802, '登记客资',     'franchise:customer:create',          3, 2,  9800, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, 'migration', 0),
        (9803, '客户跟进',     'franchise:customer:follow',          3, 3,  9800, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, 'migration', 0),
        (9804, '标准设计费用', 'franchise:design-fee-policy:manage', 3, 4,  9800, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, 'migration', 0),
        (9805, '委托查询',     'franchise:commission:query',         3, 5,  9800, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, 'migration', 0),
        (9806, '提交委托',     'franchise:commission:submit',        3, 6,  9800, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, 'migration', 0),
        (9807, '审批委托费用', 'franchise:commission:approve-fee',   3, 7,  9800, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, 'migration', 0),
        (9808, '设计完成',     'franchise:commission:complete',      3, 8,  9800, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, 'migration', 0),
        (9809, '成果下发',     'franchise:commission:dispatch',      3, 9,  9800, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, 'migration', 0),
        (9810, '修改意见',     'franchise:commission:feedback',      3, 10, 9800, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, 'migration', 0),
        (9811, '收图移交',     'franchise:commission:handover',      3, 11, 9800, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, 'migration', 0),
        (9812, '移交接收',     'franchise:commission:receive',       3, 12, 9800, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, 'migration', 0),
        (9813, '门店付款登记', 'franchise:design-payment:register',  3, 13, 9800, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, 'migration', 0),
        (9814, '门店付款复核', 'franchise:design-payment:review',    3, 14, 9800, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, 'migration', 0),
        (9815, '总部到账登记', 'franchise:design-receipt:register',  3, 15, 9800, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, 'migration', 0),
        (9816, '总部到账核实', 'franchise:design-receipt:verify',    3, 16, 9800, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, 'migration', 0),
        (9817, '待办',         'franchise:todo:query',               3, 17, 9800, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, 'migration', 0);

    -- 岗位授权：(角色, 菜单)；编号 98 + 角色序号 + 菜单序号
    INSERT INTO system_role_menu (id, role_id, menu_id, creator, deleted, tenant_id)
    SELECT 9800000 + r * 100 + (m - 9789), r + 9800, m, 'migration', 0, 1
    FROM (VALUES
        -- 业务员、业务负责人：客户、委托提交与移交
        (1, 9800), (1, 9801), (1, 9802), (1, 9803), (1, 9805), (1, 9806), (1, 9810), (1, 9811), (1, 9817),
        (2, 9800), (2, 9801), (2, 9802), (2, 9803), (2, 9805), (2, 9806), (2, 9810), (2, 9811), (2, 9817),
        -- 工程人员：查看委托、待办
        (3, 9800), (3, 9805), (3, 9817),
        -- 工程负责人：接收移交
        (4, 9800), (4, 9805), (4, 9812), (4, 9817),
        -- 财务专员：门店付款登记、总部到账登记
        (5, 9800), (5, 9805), (5, 9813), (5, 9815), (5, 9817),
        -- 财务负责人：另加复核、核实
        (6, 9800), (6, 9805), (6, 9813), (6, 9814), (6, 9815), (6, 9816), (6, 9817),
        -- 老板：客户与委托查看、费用审批、标准费用、员工微信绑定审计
        (7, 9800), (7, 9790), (7, 9801), (7, 9804), (7, 9805), (7, 9807), (7, 9817),
        -- 门店负责人：查看与绑定审计
        (8, 9800), (8, 9790), (8, 9801), (8, 9805), (8, 9817),
        -- 设计行政：设计完成、成果下发
        (9, 9800), (9, 9805), (9, 9808), (9, 9809), (9, 9817)
    ) AS grants(r, m);
END $$;
