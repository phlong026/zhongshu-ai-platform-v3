-- 仅供B隔离停写环境回滚；任何账户、流水、额度或权限数据存在时拒绝破坏性删表。
DO $$ BEGIN
  IF current_setting('transaction_isolation') <> 'read committed' THEN
    RAISE EXCEPTION 'Company point pool rollback requires READ COMMITTED isolation';
  END IF;
  LOCK TABLE org_point_account, org_point_ledger, org_member_quota, org_member_permission IN ACCESS EXCLUSIVE MODE;
  IF EXISTS (SELECT 1 FROM org_point_account) OR EXISTS (SELECT 1 FROM org_point_ledger)
      OR EXISTS (SELECT 1 FROM org_member_quota) OR EXISTS (SELECT 1 FROM org_member_permission) THEN
    RAISE EXCEPTION 'Non-empty company point pool rollback refused';
  END IF;
  DROP TABLE org_member_permission;
  DROP TABLE org_member_quota;
  DROP TABLE org_point_ledger;
  DROP TABLE org_point_account;
  DROP FUNCTION org_point_ledger_append_only();
END $$;
