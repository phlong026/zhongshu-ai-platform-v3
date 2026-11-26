DO $$
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Customer step supplement ticket rollback requires READ COMMITTED isolation';
    END IF;
    LOCK TABLE fr_asset_download_ticket IN ACCESS EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM fr_asset_download_ticket WHERE scope_workflow_step IN ('CONTRACT','START')) THEN
        RAISE EXCEPTION 'CUSTOMER_SUPPLEMENT_TICKET_IN_USE: preserve existing step-scoped download tickets';
    END IF;
    ALTER TABLE fr_asset_download_ticket DROP CONSTRAINT fr_asset_workflow_download_scope_check;
    ALTER TABLE fr_asset_download_ticket ADD CONSTRAINT fr_asset_workflow_download_scope_check CHECK (
        (scope_customer_id IS NULL AND scope_workflow_step IS NULL)
    OR (scope_customer_id IS NOT NULL AND scope_workflow_step IS NOT NULL
        AND scope_workflow_step IN ('INVITATION','INTRODUCTION','HOUSE_SELECTION','SITE_VISIT','MEASUREMENT','QUOTATION')
        AND owner_org_id IS NULL AND scope_commission_id IS NULL AND scope_dispatch_seq IS NULL AND scope_slot IS NULL)
    );
END $$;
