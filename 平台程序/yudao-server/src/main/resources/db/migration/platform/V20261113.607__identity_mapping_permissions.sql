-- 24 号任务书 B-15：旧设计账号身份映射权限（超管）
DO $$
BEGIN
  IF to_regclass('public.system_menu') IS NULL OR to_regclass('public.system_role_menu') IS NULL THEN
    RETURN;
  END IF;

  INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component,
                           component_name, status, visible, keep_alive, always_show,
                           creator, create_time, updater, update_time, deleted) VALUES
  (9572, '查询身份映射', 'identity:identity-mapping:query', 3, 7, 9509, '', '', NULL, NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9573, '维护身份映射', 'identity:identity-mapping:manage', 3, 8, 9509, '', '', NULL, NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0);

  INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
  SELECT 959200 + id, 1, id, '', now(), '', now(), 0, 0 FROM system_menu WHERE id BETWEEN 9572 AND 9573;
END $$;
