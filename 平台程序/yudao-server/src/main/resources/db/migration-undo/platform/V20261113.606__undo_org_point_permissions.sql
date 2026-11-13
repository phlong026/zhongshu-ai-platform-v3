-- V20261113.606 回滚：移除公司积分池平台管理权限（缺表环境同样安全跳过）
DO $$
BEGIN
  IF to_regclass('public.system_menu') IS NULL OR to_regclass('public.system_role_menu') IS NULL THEN
    RETURN;
  END IF;
  DELETE FROM system_role_menu WHERE menu_id IN (9570, 9571);
  DELETE FROM system_menu WHERE id IN (9570, 9571);
END $$;
