-- prompt收据是防重事实；非空时只能前滚，禁止删除数据回滚。
DO $$ BEGIN
  IF current_setting('transaction_isolation') <> 'read committed' THEN
    RAISE EXCEPTION 'Prompt receipt rollback requires READ COMMITTED isolation';
  END IF;
  LOCK TABLE design_prompt_receipt IN ACCESS EXCLUSIVE MODE;
  IF EXISTS (SELECT 1 FROM design_prompt_receipt) THEN
    RAISE EXCEPTION 'Non-empty prompt receipt rollback refused';
  END IF;
  DROP TABLE design_prompt_receipt;
END $$;
