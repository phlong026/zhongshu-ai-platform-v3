-- 仅从已核验的退役前备份恢复原岗位模板；不恢复或新建任何 pf_staff_post 任职。
-- 同一会话须先加载 pg_temp.retired_design_post_template_backup，操作步骤见
-- deploy/platform/DESIGN_POST_RETIREMENT_RECOVERY.md。无备份、快照越界或现有行冲突即拒绝。
DO $$
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Design post recovery requires READ COMMITTED';
    END IF;
    PERFORM set_config('lock_timeout', '10s', true);
    PERFORM set_config('statement_timeout', '60s', true);
    IF to_regclass('pg_temp.retired_design_post_template_backup') IS NULL THEN
        RAISE EXCEPTION 'Verified pre-retirement template backup is required';
    END IF;
    LOCK TABLE pf_post_template IN SHARE ROW EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM pg_temp.retired_design_post_template_backup
               WHERE role_code NOT IN ('hq-designer', 'hq-design-lead', 'hq-design-admin')
                  OR domain <> 'franchise') THEN
        RAISE EXCEPTION 'Template backup contains unrelated roles';
    END IF;
    IF EXISTS (SELECT 1 FROM pf_post_template t
               JOIN pg_temp.retired_design_post_template_backup b USING (role_code)
               WHERE to_jsonb(t) IS DISTINCT FROM to_jsonb(b)) THEN
        RAISE EXCEPTION 'Template recovery conflicts with current data';
    END IF;
    INSERT INTO pf_post_template
    SELECT b.* FROM pg_temp.retired_design_post_template_backup b
    WHERE NOT EXISTS (SELECT 1 FROM pf_post_template t WHERE t.role_code = b.role_code);
END $$;
