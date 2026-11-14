DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM fr_asset WHERE asset_type='DESIGN_REFERENCE') THEN
        RAISE EXCEPTION 'B design reference assets exist; preserve objects and metadata before rollback';
    END IF;
END $$;
ALTER TABLE fr_asset DROP CONSTRAINT IF EXISTS fr_asset_asset_type_check;
ALTER TABLE fr_asset ADD CONSTRAINT fr_asset_asset_type_check CHECK (
    asset_type IN ('FIN_VOUCHER','ENG_DRAWING','ENG_SITE_PHOTO','CONTRACT_ATTACHMENT','CONTENT_ASSET')
);
