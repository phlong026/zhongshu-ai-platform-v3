-- 已有开通记录、已交付凭据或业务数据时必须先备份并制定回退安排，禁止直接删除。
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pf_franchise_account) OR EXISTS (SELECT 1 FROM pf_staff_password_policy) THEN
        RAISE EXCEPTION '加盟商账号表非空，请保留用户与业务数据，停用新入口并备份后人工处理';
    END IF;
END $$;
DROP TABLE pf_franchise_account_alias;
DROP TABLE pf_staff_password_policy;
DROP TABLE pf_franchise_account;
