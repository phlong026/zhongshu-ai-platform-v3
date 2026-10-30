-- 「AI任务」菜单更名「AI 生成记录」（2026-10-09 运营需求④）
--
-- 背景：生成任务列表原内嵌于点数流水页，V009 种子的 9525 页面行名为「AI任务」；
-- 现恢复为独立菜单页（前端路由 /zs/ai-job 渲染 zs/job/index），菜单名对齐页面职责。
-- 仅改名称；permission（aiorchestration:job:query）与角色绑定不动。
DO $$
BEGIN
  IF to_regclass('public.system_menu') IS NULL THEN
    RAISE NOTICE 'system_menu 不存在，跳过 AI 生成记录菜单更名';
    RETURN;
  END IF;
  UPDATE system_menu SET name = 'AI 生成记录', update_time = now()
  WHERE id = 9525 AND deleted = 0;
END $$;
