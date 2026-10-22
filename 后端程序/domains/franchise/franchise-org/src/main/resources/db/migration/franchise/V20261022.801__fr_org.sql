-- V20261022.801：加盟协同域组织配置（M2.2）
-- 注册组织类型「总部 / 门店」与 13 个岗位模板；岗位编码与小程序 organization.js 一致。
-- 岗位对应的 yudao 角色、菜单与权限码在 db/migration/franchise-system（只在完整库执行）。

INSERT INTO pf_org_type (code, domain, name)
VALUES ('headquarters', 'franchise', '总部'),
       ('store', 'franchise', '加盟门店');

INSERT INTO pf_post_template (role_code, domain, org_types, line, lead, inherits_role_code)
VALUES ('sales', 'franchise', 'store', 'sales', FALSE, NULL),
       ('sales-lead', 'franchise', 'store', 'sales', TRUE, 'sales'),
       ('engineer', 'franchise', 'store', 'engineering', FALSE, NULL),
       ('project-manager', 'franchise', 'store', 'engineering', TRUE, 'engineer'),
       ('finance', 'franchise', 'headquarters,store', 'finance', FALSE, NULL),
       ('finance-lead', 'franchise', 'headquarters,store', 'finance', TRUE, 'finance'),
       ('boss', 'franchise', 'headquarters,store', 'management', FALSE, NULL),
       ('owner', 'franchise', 'store', 'management', FALSE, NULL),
       ('hq-designer', 'franchise', 'headquarters,store', 'design', FALSE, NULL),
       ('hq-design-lead', 'franchise', 'headquarters,store', 'design', TRUE, 'hq-designer'),
       ('hq-design-admin', 'franchise', 'headquarters', 'design', FALSE, NULL),
       ('hq-operations', 'franchise', 'headquarters', 'operations', FALSE, NULL),
       ('hq-operations-lead', 'franchise', 'headquarters', 'operations', TRUE, 'hq-operations');
