-- B 自有资产类型与短期范围票据；不修改 A 资产表或权限。
ALTER TABLE fr_asset DROP CONSTRAINT IF EXISTS fr_asset_asset_type_check;
ALTER TABLE fr_asset ADD CONSTRAINT fr_asset_asset_type_check CHECK (
    asset_type IN ('FIN_VOUCHER','ENG_DRAWING','ENG_SITE_PHOTO','CONTRACT_ATTACHMENT','CONTENT_ASSET','DESIGN_REFERENCE','DESIGN_DELIVERABLE')
);
ALTER TABLE fr_asset_download_ticket ADD COLUMN owner_org_id BIGINT;
ALTER TABLE fr_asset_download_ticket ADD COLUMN scope_commission_id BIGINT;
ALTER TABLE fr_asset_download_ticket ADD COLUMN scope_dispatch_seq INTEGER;
ALTER TABLE fr_asset_download_ticket ADD COLUMN scope_slot VARCHAR(16);
ALTER TABLE fr_asset_download_ticket ADD CONSTRAINT fr_asset_download_scope_check CHECK (
    (owner_org_id IS NULL AND scope_commission_id IS NULL AND scope_dispatch_seq IS NULL AND scope_slot IS NULL)
    OR (owner_org_id IS NOT NULL AND scope_commission_id IS NOT NULL AND scope_dispatch_seq IS NOT NULL
        AND scope_dispatch_seq > 0 AND scope_slot IS NOT NULL AND scope_slot IN ('RESULT','DRAWING','REVIEW','CONFIRMATION'))
);
