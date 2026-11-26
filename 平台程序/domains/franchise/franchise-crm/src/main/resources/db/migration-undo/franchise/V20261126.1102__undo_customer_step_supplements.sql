DO $$
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Customer step supplement rollback requires READ COMMITTED isolation';
    END IF;
    LOCK TABLE fr_customer_step_supplement IN ACCESS EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM fr_customer_step_supplement) THEN
        RAISE EXCEPTION 'CUSTOMER_SUPPLEMENT_IN_USE: preserve customer records and attachment references';
    END IF;
    DROP TABLE fr_customer_step_supplement;
    DROP INDEX uk_fr_customer_supplement_scope;
END $$;
