-- A design only; consumer estimates retain their original billing route.
ALTER TABLE budget_estimate ADD COLUMN billing_mode VARCHAR(24) NOT NULL DEFAULT 'CONSUMER_POINTS';
ALTER TABLE budget_estimate ADD COLUMN cost_business_id VARCHAR(128);
ALTER TABLE budget_estimate ADD CONSTRAINT budget_estimate_cost_subject CHECK (
    (billing_mode='CONSUMER_POINTS' AND cost_business_id IS NULL) OR
    (billing_mode='SERVICE_COST' AND cost_business_id IS NOT NULL)
);
CREATE UNIQUE INDEX budget_estimate_service_receipt ON budget_estimate(cost_business_id) WHERE cost_business_id IS NOT NULL;
