-- Reserve a disjoint owner namespace. Validation checks every existing consumer account and
-- refuses deployment if historical IDs overlap; future inserts/updates cannot cross this boundary.
DO $$
BEGIN
    IF to_regclass('account') IS NOT NULL THEN
        ALTER TABLE account ADD CONSTRAINT ck_account_consumer_namespace
            CHECK (id < 8000000000000000000) NOT VALID;
        ALTER TABLE account VALIDATE CONSTRAINT ck_account_consumer_namespace;
    END IF;
END $$;
-- The provider readiness check requires this validated constraint in a full A runtime.
-- Isolated receipt/mapping tests can migrate without the consumer schema.
-- Rollback after disabling the provider: ALTER TABLE account DROP CONSTRAINT ck_account_consumer_namespace;
