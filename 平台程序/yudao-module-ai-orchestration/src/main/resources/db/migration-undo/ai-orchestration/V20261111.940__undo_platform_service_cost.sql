-- Local rollback before service traffic only. Never erase recorded internal cost facts.
BEGIN;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM platform_cost_business) THEN
        RAISE EXCEPTION 'Service cost records exist; retain schema and roll back application only';
    END IF;
END $$;
ALTER TABLE ai_job DROP CONSTRAINT ai_job_billing_subject;
ALTER TABLE ai_job DROP COLUMN cost_business_id;
ALTER TABLE ai_job DROP COLUMN billing_mode;
DROP TABLE platform_cost_ledger;
DROP FUNCTION platform_cost_ledger_append_only();
DROP TABLE platform_cost_business;
DROP TABLE platform_cost_price;
COMMIT;
