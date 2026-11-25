DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM fr_customer_payment_plan_draft_version) THEN
 RAISE EXCEPTION 'Draft payment plan history exists; restore a verified snapshot instead of discarding evidence';
 END IF;
END $$;
DROP TABLE fr_customer_payment_plan_draft_version;
DROP TABLE fr_customer_payment_plan_draft;
