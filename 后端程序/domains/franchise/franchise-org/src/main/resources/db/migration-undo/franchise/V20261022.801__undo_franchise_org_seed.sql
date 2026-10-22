-- 回滚 V20261022.801：删除加盟协同域的岗位授权、菜单、角色、岗位模板与组织类型。
-- 前提：已没有使用这些岗位的任职、没有这些类型的组织（否则外键阻止删除，需先清理）。
DO $$
BEGIN
    IF to_regclass('system_role_menu') IS NOT NULL THEN
        DELETE FROM system_role_menu WHERE role_id BETWEEN 9801 AND 9809;
        DELETE FROM system_user_role WHERE role_id BETWEEN 9801 AND 9809;
        DELETE FROM system_menu WHERE id = 9790 OR id BETWEEN 9800 AND 9817;
        DELETE FROM system_role WHERE id BETWEEN 9801 AND 9809;
    END IF;
END $$;
DELETE FROM pf_post_template WHERE domain = 'franchise';
DELETE FROM pf_org_type WHERE domain = 'franchise';
