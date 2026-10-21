-- 回滚 V20261009.013：恢复「AI任务」菜单名
DO $$
BEGIN
  IF to_regclass('public.system_menu') IS NULL THEN
    RETURN;
  END IF;
  UPDATE system_menu SET name = 'AI任务', update_time = now()
  WHERE id = 9525 AND deleted = 0;
END $$;
