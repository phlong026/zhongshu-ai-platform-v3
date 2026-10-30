-- 回滚 V20261016.602__rename_inspiration_menus.sql：恢复「户型库」原名
DO $$
BEGIN
  IF to_regclass('public.system_menu') IS NULL THEN
    RETURN;
  END IF;

  UPDATE system_menu SET name = '户型库', update_time = now() WHERE id = 9501 AND deleted = 0;
  UPDATE system_menu SET name = '户型库管理', update_time = now() WHERE id = 9502 AND deleted = 0;
END $$;
