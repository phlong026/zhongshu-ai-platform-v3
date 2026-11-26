DO $$ BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Payroll payment rollback requires READ COMMITTED';
    END IF;
    LOCK TABLE fr_fin_payable IN ACCESS EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM fr_fin_payable WHERE payroll_record_id IS NOT NULL) THEN
        RAISE EXCEPTION 'Payroll payments are linked; preserve those associations before rollback';
    END IF;
    ALTER TABLE fr_fin_payable DROP COLUMN payroll_record_id;
    ALTER TABLE fr_fin_payroll_record DROP CONSTRAINT uq_fr_payroll_record_company_id;
END $$;
