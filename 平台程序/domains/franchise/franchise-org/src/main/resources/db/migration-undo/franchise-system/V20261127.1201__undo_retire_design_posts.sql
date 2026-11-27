-- 从已核验的退役前备份恢复真实角色 ID、tenant_id、原菜单及用户绑定；不猜种子、不改任职。
-- 同一会话须先加载 retired_design_role_backup / retired_design_role_menu_backup /
-- retired_design_user_role_backup 临时表，步骤见 deploy/platform/DESIGN_POST_RETIREMENT_RECOVERY.md。
-- 一个 DO 块保证原子性；重复的同一快照为空操作，任何当前冲突整段拒绝。
DO $$
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Design role recovery requires READ COMMITTED';
    END IF;
    PERFORM set_config('lock_timeout', '10s', true);
    PERFORM set_config('statement_timeout', '60s', true);
    IF to_regclass('pg_temp.retired_design_role_backup') IS NULL
       OR to_regclass('pg_temp.retired_design_role_menu_backup') IS NULL
       OR to_regclass('pg_temp.retired_design_user_role_backup') IS NULL THEN
        RAISE EXCEPTION 'Verified pre-retirement role and binding backups are required';
    END IF;
    LOCK TABLE system_role, system_role_menu, system_user_role IN SHARE ROW EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM pg_temp.retired_design_role_backup
               WHERE code NOT IN ('hq-designer', 'hq-design-lead', 'hq-design-admin')) THEN
        RAISE EXCEPTION 'Role backup contains unrelated roles';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_temp.retired_design_role_menu_backup b
               WHERE NOT EXISTS (SELECT 1 FROM pg_temp.retired_design_role_backup r
                                 WHERE r.id = b.role_id AND r.tenant_id = b.tenant_id)
                  OR NOT EXISTS (SELECT 1 FROM system_menu m WHERE m.id = b.menu_id))
       OR EXISTS (SELECT 1 FROM pg_temp.retired_design_user_role_backup b
                  WHERE NOT EXISTS (SELECT 1 FROM pg_temp.retired_design_role_backup r
                                    WHERE r.id = b.role_id AND r.tenant_id = b.tenant_id)
                     OR NOT EXISTS (SELECT 1 FROM system_users u
                                    WHERE u.id = b.user_id AND u.tenant_id = b.tenant_id)) THEN
        RAISE EXCEPTION 'Binding backup has missing or mismatched role, tenant, menu or user';
    END IF;
    IF EXISTS (SELECT 1 FROM system_role r JOIN pg_temp.retired_design_role_backup b USING (id)
               WHERE to_jsonb(r) IS DISTINCT FROM to_jsonb(b))
       OR EXISTS (SELECT 1 FROM system_role r JOIN pg_temp.retired_design_role_backup b
                  ON r.tenant_id = b.tenant_id AND r.code = b.code
                  WHERE NOT EXISTS (SELECT 1 FROM pg_temp.retired_design_role_backup original WHERE original.id = r.id))
       OR EXISTS (SELECT 1 FROM system_role_menu r JOIN pg_temp.retired_design_role_menu_backup b USING (id)
                  WHERE to_jsonb(r) IS DISTINCT FROM to_jsonb(b))
       OR EXISTS (SELECT 1 FROM system_user_role r JOIN pg_temp.retired_design_user_role_backup b USING (id)
                  WHERE to_jsonb(r) IS DISTINCT FROM to_jsonb(b))
       OR EXISTS (SELECT 1 FROM system_role_menu r JOIN pg_temp.retired_design_role_menu_backup b
                  ON r.tenant_id = b.tenant_id AND r.role_id = b.role_id AND r.menu_id = b.menu_id
                  WHERE NOT EXISTS (SELECT 1 FROM pg_temp.retired_design_role_menu_backup original WHERE original.id = r.id))
       OR EXISTS (SELECT 1 FROM system_user_role r JOIN pg_temp.retired_design_user_role_backup b
                  ON r.tenant_id = b.tenant_id AND r.role_id = b.role_id AND r.user_id = b.user_id
                  WHERE NOT EXISTS (SELECT 1 FROM pg_temp.retired_design_user_role_backup original WHERE original.id = r.id)) THEN
        RAISE EXCEPTION 'Role recovery conflicts with current identities or bindings';
    END IF;
    INSERT INTO system_role
    SELECT b.* FROM pg_temp.retired_design_role_backup b
    WHERE NOT EXISTS (SELECT 1 FROM system_role r WHERE r.id = b.id);
    INSERT INTO system_role_menu
    SELECT b.* FROM pg_temp.retired_design_role_menu_backup b
    WHERE NOT EXISTS (SELECT 1 FROM system_role_menu r WHERE r.id = b.id);
    INSERT INTO system_user_role
    SELECT b.* FROM pg_temp.retired_design_user_role_backup b
    WHERE NOT EXISTS (SELECT 1 FROM system_user_role r WHERE r.id = b.id);
END $$;
