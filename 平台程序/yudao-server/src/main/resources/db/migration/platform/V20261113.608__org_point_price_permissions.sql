-- 24 号 D8：公司池科目价格后台管理权限（超管）
DO $$
BEGIN
  IF to_regclass('public.system_menu') IS NULL OR to_regclass('public.system_role_menu') IS NULL THEN
    RETURN;
  END IF;
  INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component,
                           component_name, status, visible, keep_alive, always_show,
                           creator, create_time, updater, update_time, deleted) VALUES
  (9574, '查询科目价格', 'commerce:org-point-price:query', 3, 7, 9509, '', '', NULL, NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9575, '维护科目价格', 'commerce:org-point-price:manage', 3, 8, 9509, '', '', NULL, NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0);
  INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
  SELECT 959300 + id, 1, id, '', now(), '', now(), 0, 0 FROM system_menu WHERE id BETWEEN 9574 AND 9575;
END $$;
