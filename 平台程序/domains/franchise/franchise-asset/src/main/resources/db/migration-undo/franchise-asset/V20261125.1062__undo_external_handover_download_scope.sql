DELETE FROM fr_asset_download_ticket WHERE scope_slot IN ('EXT_RESULT','EXT_DRAWING','EXT_REVIEW','EXT_CONFIRM');
ALTER TABLE fr_asset_download_ticket DROP CONSTRAINT fr_asset_download_scope_check;
ALTER TABLE fr_asset_download_ticket ADD CONSTRAINT fr_asset_download_scope_check CHECK (
    (owner_org_id IS NULL AND scope_commission_id IS NULL AND scope_dispatch_seq IS NULL AND scope_slot IS NULL)
    OR (owner_org_id IS NOT NULL AND scope_commission_id IS NOT NULL AND scope_dispatch_seq IS NOT NULL
        AND scope_dispatch_seq>0 AND scope_slot IS NOT NULL AND scope_slot IN ('RESULT','DRAWING','REVIEW','CONFIRMATION')));
