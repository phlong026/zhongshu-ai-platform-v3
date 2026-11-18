LOCK TABLE pf_recharge_order IN ACCESS EXCLUSIVE MODE;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pf_recharge_order WHERE credit_target = 'COMPANY') THEN
    RAISE EXCEPTION 'Company recharge orders require their original credit destination';
  END IF;
END $$;
DROP INDEX pf_company_recharge_current;
ALTER TABLE pf_recharge_order DROP COLUMN credit_target;
