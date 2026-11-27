-- V20261127.1201：设计岗位退役（2026-11-27 业务决定：设计由外部软件线下完成，系统内只保留
-- 设计文件上传与移交；详见小程序 docs/business-eight-step-20261125/design-posts-retirement-20261127.md）。
-- 处置：撤销三岗在岗任职 → 删除岗位模板（不可再建岗）→ 删除角色及其菜单/用户绑定。
-- 角色行删除后，LEGACY 委托推进等历史代码分支成为不可达死代码，随 LEGACY 服务端退役批一并清理；
-- 历史数据里的岗位编码（fr_commission 等表）不受影响，展示层仍可用名称字典翻译。
-- 回滚：db/migration-undo/franchise-system/V20261127.1201__undo_retire_design_posts.sql

-- 1) 撤销在岗任职（保留行作任职历史，CurrentPost/待办均按 ACTIVE 过滤）
UPDATE pf_staff_post SET status = 'INACTIVE'
WHERE role_code IN ('hq-designer', 'hq-design-lead', 'hq-design-admin') AND status = 'ACTIVE';

-- 2) 删除岗位模板：总部/门店不再可选设计岗（后端建岗接口按模板校验，一并封死）
DELETE FROM pf_post_template
WHERE domain = 'franchise' AND role_code IN ('hq-designer', 'hq-design-lead', 'hq-design-admin');

-- 3) 删除角色与绑定（全租户）
DELETE FROM system_role_menu WHERE role_id IN (SELECT id FROM system_role WHERE code IN ('hq-designer', 'hq-design-lead', 'hq-design-admin'));
DELETE FROM system_user_role WHERE role_id IN (SELECT id FROM system_role WHERE code IN ('hq-designer', 'hq-design-lead', 'hq-design-admin'));
DELETE FROM system_role WHERE code IN ('hq-designer', 'hq-design-lead', 'hq-design-admin');
