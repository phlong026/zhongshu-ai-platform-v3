ALTER TABLE fr_asset_download_ticket ADD COLUMN scope_contract_id BIGINT;
ALTER TABLE fr_asset_download_ticket ADD CONSTRAINT fr_asset_contract_download_scope_check CHECK (
    scope_contract_id IS NULL
    OR (scope_contract_id > 0 AND owner_org_id IS NULL AND scope_commission_id IS NULL
        AND scope_dispatch_seq IS NULL AND scope_slot IS NULL
        AND scope_customer_id IS NULL AND scope_workflow_step IS NULL)
);
