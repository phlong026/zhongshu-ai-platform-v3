-- V20261127.1200 回滚：恢复设计三岗岗位模板（851 种子原样）。撤销的任职不自动复活，需按人事流程重新指派。
INSERT INTO pf_post_template (role_code, domain, org_types, line, lead, inherits_role_code)
SELECT v.role_code, 'franchise', v.org_types, 'design', v.lead, v.inherits
FROM (VALUES
       ('hq-designer', 'headquarters,store', FALSE, NULL::varchar),
       ('hq-design-lead', 'headquarters,store', TRUE, 'hq-designer'),
       ('hq-design-admin', 'headquarters', FALSE, NULL::varchar)
     ) AS v(role_code, org_types, lead, inherits)
WHERE NOT EXISTS (SELECT 1 FROM pf_post_template t WHERE t.role_code = v.role_code AND t.domain = 'franchise');
