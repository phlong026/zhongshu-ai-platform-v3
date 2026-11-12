BEGIN;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM budget_estimate WHERE billing_mode='SERVICE_COST') THEN
        RAISE EXCEPTION 'Service budgets exist; retain billing identity and roll back application only';
    END IF;
END $$;
DROP INDEX budget_estimate_service_receipt;
ALTER TABLE budget_estimate DROP CONSTRAINT budget_estimate_cost_subject;
ALTER TABLE budget_estimate DROP COLUMN cost_business_id;
ALTER TABLE budget_estimate DROP COLUMN billing_mode;
COMMIT;
