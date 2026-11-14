-- 计费归属是业务事实；非空时只能前滚，禁止删除数据回滚。
DO $$ BEGIN
  IF current_setting('transaction_isolation') <> 'read committed' THEN
    RAISE EXCEPTION 'Billing binding rollback requires READ COMMITTED isolation';
  END IF;
  LOCK TABLE design_billing_binding IN ACCESS EXCLUSIVE MODE;
  IF EXISTS (SELECT 1 FROM design_billing_binding) THEN
    RAISE EXCEPTION 'Non-empty billing binding rollback refused';
  END IF;
  DROP TABLE design_billing_binding;
END $$;
