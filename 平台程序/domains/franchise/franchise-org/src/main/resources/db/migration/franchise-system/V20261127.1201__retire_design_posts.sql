-- V20261127.1201：设计岗位退役——删除角色与菜单/用户绑定（任职撤销与模板删除见 franchise/V20261127.1200）。
-- 角色行删除后，LEGACY 委托推进等历史代码分支成为不可达死代码，随 LEGACY 服务端退役批一并清理；
-- 历史数据里的岗位编码不受影响，展示层仍可用名称字典翻译。
-- 回滚：db/migration-undo/franchise-system/V20261127.1201__undo_retire_design_posts.sql

DELETE FROM system_role_menu WHERE role_id IN (SELECT id FROM system_role WHERE code IN ('hq-designer', 'hq-design-lead', 'hq-design-admin'));
DELETE FROM system_user_role WHERE role_id IN (SELECT id FROM system_role WHERE code IN ('hq-designer', 'hq-design-lead', 'hq-design-admin'));
DELETE FROM system_role WHERE code IN ('hq-designer', 'hq-design-lead', 'hq-design-admin');
