-- V20261127.1201 回滚：恢复设计三岗的字典、模板与菜单绑定（按 851/853 种子原样）。
-- 注意：已撤销的任职（pf_staff_post INACTIVE）不自动复活，需按人事流程重新指派。

-- 1) 恢复角色行（全租户通用形态，按 851 种子原样；tenant 维度按需补插）
INSERT INTO system_role (id, name, code, sort, data_scope, data_scope_dept_ids, status, type, remark, creator, updater)
SELECT v.id, v.name, v.code, v.sort, 1, '', 0, 1, '加盟协同域岗位', '1', '1'
FROM (VALUES
       (810009, '设计师', 'hq-designer', 109),
       (810010, '设计负责人', 'hq-design-lead', 110),
       (810011, '设计行政', 'hq-design-admin', 111)
     ) AS v(id, name, code, sort)
CROSS JOIN (SELECT DISTINCT tenant_id FROM system_role) t
WHERE NOT EXISTS (SELECT 1 FROM system_role r WHERE r.tenant_id = t.tenant_id AND r.code = v.code);

-- 2) 恢复 851 菜单绑定（hq-design-admin：810107 查看设计委托、810110 设计完成与成果下发）
INSERT INTO system_role_menu (id, role_id, menu_id, creator, updater, tenant_id)
SELECT (SELECT COALESCE(MAX(rm.id), 811000) FROM system_role_menu rm) + row_number() OVER (ORDER BY m.id), r.id, m.id, '1', '1', r.tenant_id
FROM system_role r
JOIN system_menu m ON m.id IN (810107, 810110)
WHERE r.code = 'hq-design-admin'
  AND NOT EXISTS (SELECT 1 FROM system_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.id);

-- 3) 恢复 853 菜单绑定（hq-design-admin：710301 franchise:design:use）
INSERT INTO system_role_menu (id, role_id, menu_id, creator, updater, tenant_id)
SELECT (SELECT COALESCE(MAX(rm.id), 713000) FROM system_role_menu rm) + row_number() OVER (ORDER BY r.id), r.id, 710301, '1', '1', r.tenant_id
FROM system_role r
WHERE r.code = 'hq-design-admin'
  AND EXISTS (SELECT 1 FROM system_menu sm WHERE sm.id = 710301)
  AND NOT EXISTS (SELECT 1 FROM system_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 710301);

-- 4) 恢复岗位模板（851 种子原样）
INSERT INTO pf_post_template (role_code, domain, org_types, line, lead, inherits_role_code)
SELECT v.role_code, 'franchise', v.org_types, 'design', v.lead, v.inherits
FROM (VALUES
       ('hq-designer', 'headquarters,store', FALSE, NULL::varchar),
       ('hq-design-lead', 'headquarters,store', TRUE, 'hq-designer'),
       ('hq-design-admin', 'headquarters', FALSE, NULL::varchar)
     ) AS v(role_code, org_types, lead, inherits)
WHERE NOT EXISTS (SELECT 1 FROM pf_post_template t WHERE t.role_code = v.role_code AND t.domain = 'franchise');
