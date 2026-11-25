DO $$ BEGIN
    IF EXISTS(SELECT 1 FROM fr_fin_payroll_record) OR EXISTS(SELECT 1 FROM fr_fin_payroll_rule) THEN
        RAISE EXCEPTION 'Payroll history exists; preserve the records before rollback';
    END IF;
END $$;
DROP TABLE fr_fin_payroll_record;
DROP TABLE fr_fin_payroll_rule;
DROP FUNCTION fr_payroll_snapshot_immutable();
