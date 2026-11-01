-- V20261101.706：sales-lead 增授「查看本组织成员」（S1-11a）。
-- 责任转交候选源 orgApi.orgStaff 需 platform:org:query（703 只授了 boss/owner），
-- sales-lead 有转交权限但取不到候选——本迁移补授权；范围校验（本组织）仍在服务内二次把关。

INSERT INTO system_role_menu (id, role_id, menu_id, creator, updater, tenant_id)
SELECT 711010, r.id, m.id, '1', '1', 1
FROM (VALUES ('sales-lead', 'platform:org:query')) AS rm(role_code, permission)
JOIN system_role r ON r.code = rm.role_code AND r.tenant_id = 1
JOIN system_menu m ON m.permission = rm.permission;
