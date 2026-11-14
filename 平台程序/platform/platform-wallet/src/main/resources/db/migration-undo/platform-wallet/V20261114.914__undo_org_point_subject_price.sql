-- 价格版本影响预扣决策；非空时只能前滚，禁止删除配置回滚。
DO $$ BEGIN
  IF current_setting('transaction_isolation') <> 'read committed' THEN
    RAISE EXCEPTION 'Subject price rollback requires READ COMMITTED isolation';
  END IF;
  LOCK TABLE org_point_subject_price IN ACCESS EXCLUSIVE MODE;
  IF EXISTS (SELECT 1 FROM org_point_subject_price) THEN
    RAISE EXCEPTION 'Non-empty subject price rollback refused';
  END IF;
  DROP TABLE org_point_subject_price;
END $$;
