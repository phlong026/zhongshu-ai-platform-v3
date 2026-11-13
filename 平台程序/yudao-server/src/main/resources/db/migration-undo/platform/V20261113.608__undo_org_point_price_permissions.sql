DO $$
BEGIN
  IF to_regclass('public.system_menu') IS NULL OR to_regclass('public.system_role_menu') IS NULL THEN
    RETURN;
  END IF;
  DELETE FROM system_role_menu WHERE menu_id IN (9574, 9575);
  DELETE FROM system_menu WHERE id IN (9574, 9575);
END $$;
