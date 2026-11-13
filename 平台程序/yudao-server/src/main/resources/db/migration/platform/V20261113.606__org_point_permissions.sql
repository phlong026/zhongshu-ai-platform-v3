-- 24 号任务书 B-9/B-10：公司积分池平台管理权限（超管）
DO $$
BEGIN
  IF to_regclass('public.system_menu') IS NULL OR to_regclass('public.system_role_menu') IS NULL THEN
    RETURN;
  END IF;

  INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component,
                           component_name, status, visible, keep_alive, always_show,
                           creator, create_time, updater, update_time, deleted) VALUES
  (9570, '查询公司积分池', 'commerce:org-point:query', 3, 5, 9509, '', '', NULL, NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9571, '公司积分池调点', 'commerce:org-point:adjust', 3, 6, 9509, '', '', NULL, NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0);

  INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
  SELECT 959100 + id, 1, id, '', now(), '', now(), 0, 0 FROM system_menu WHERE id BETWEEN 9570 AND 9571;
END $$;
