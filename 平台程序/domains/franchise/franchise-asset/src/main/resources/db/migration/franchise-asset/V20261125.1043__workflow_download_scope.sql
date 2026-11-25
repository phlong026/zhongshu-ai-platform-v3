ALTER TABLE fr_asset_download_ticket ADD COLUMN scope_customer_id BIGINT;
ALTER TABLE fr_asset_download_ticket ADD COLUMN scope_workflow_step VARCHAR(32);
ALTER TABLE fr_asset_download_ticket ADD CONSTRAINT fr_asset_workflow_download_scope_check CHECK (
    (scope_customer_id IS NULL AND scope_workflow_step IS NULL)
    OR (scope_customer_id IS NOT NULL AND scope_workflow_step IS NOT NULL
        AND scope_workflow_step IN ('INVITATION','INTRODUCTION','HOUSE_SELECTION','SITE_VISIT','MEASUREMENT','QUOTATION')
        AND owner_org_id IS NULL AND scope_commission_id IS NULL AND scope_dispatch_seq IS NULL AND scope_slot IS NULL)
);
