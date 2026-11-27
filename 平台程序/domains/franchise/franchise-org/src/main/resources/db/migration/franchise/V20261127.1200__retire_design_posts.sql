-- V20261127.1200：设计岗位退役——撤销在岗任职并删除岗位模板（2026-11-27 业务决定：
-- 设计由外部软件线下完成，系统内只保留设计文件上传与移交；
-- 详见小程序 docs/business-eight-step-20261125/design-posts-retirement-20261127.md）。
-- 角色行与菜单绑定在 franchise-system/V20261127.1201 处理。
-- 回滚：db/migration-undo/franchise/V20261127.1200__undo_retire_design_posts.sql

-- 1) 撤销在岗任职（保留行作任职历史，CurrentPost/待办均按 ACTIVE 过滤）
UPDATE pf_staff_post SET status = 'INACTIVE'
WHERE role_code IN ('hq-designer', 'hq-design-lead', 'hq-design-admin') AND status = 'ACTIVE';

-- 2) 删除岗位模板：总部/门店不再可选设计岗（后端建岗接口按模板校验，一并封死）
DELETE FROM pf_post_template
WHERE domain = 'franchise' AND role_code IN ('hq-designer', 'hq-design-lead', 'hq-design-admin');
