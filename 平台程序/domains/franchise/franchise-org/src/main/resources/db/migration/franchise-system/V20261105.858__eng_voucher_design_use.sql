-- V20261105.858：M07-T04 批次#36——工程凭据字节通道窄权限码。
-- R2 P1-1：不授 franchise:design:use（类级门控 DesignAccessController 全部 AI 任务/选版/改稿——授权面过大）；
-- 新建窄码 franchise:asset:bytes 仅门控 dev 资产字节通道两端点（上传/读取，DesignDevController @PreAuthorize）。
-- 矩阵：engineer/project-manager（开工/节点/现场/交付凭据办理人）+finance/finance-lead（收款凭据登记人）。
-- 菜单 710800 段（857 用 710700），role_menu id 714400 段（856 用 714200、857 用 714300——错开防撞）。
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, updater)
VALUES (710800, '工程凭据通道', 'franchise:asset:bytes', 3, 1, 0, '', '', NULL, NULL, 0, TRUE, TRUE, TRUE, '1', '1');

INSERT INTO system_role_menu (id, role_id, menu_id, creator, updater, tenant_id)
SELECT 714400 + row_number() OVER (ORDER BY r.id), r.id, 710800, '1', '1', 1
FROM (VALUES ('engineer'), ('project-manager'), ('finance'), ('finance-lead')) AS rm(role_code)
JOIN system_role r ON r.code = rm.role_code AND r.tenant_id = 1;
