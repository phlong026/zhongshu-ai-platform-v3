-- 灵感库改版（2026-10-16 需求口径）：后台菜单「户型库」更名为「灵感库」
--
-- 前置核实（grep 结论）：
-- * 9501/9502 仅由 V20260905.004__zhongshu_admin_menus.sql 种子插入（底座基线 ruoyi-vue-pro.sql 无这两行）；
-- * 全部菜单种子中名称含「户型库」的仅 9501（户型库）与 9502（户型库管理），
--   9503 审核菜单名为「AI案例审核」，无需改名；
-- * V20261009.012 设计师角色种子的「户型库管理」仅出现在注释与角色描述里，非菜单名，不在本迁移范围。
--
-- system 表缺失的环境（如仅加载业务迁移基线的合同测试容器）安全空转，不阻断迁移。
DO $$
BEGIN
  IF to_regclass('public.system_menu') IS NULL THEN
    RAISE NOTICE 'system_menu 不存在（仅迁移基线环境），跳过菜单改名';
    RETURN;
  END IF;

  UPDATE system_menu SET name = '灵感库', update_time = now() WHERE id = 9501 AND deleted = 0;
  UPDATE system_menu SET name = '灵感库管理', update_time = now() WHERE id = 9502 AND deleted = 0;
END $$;
