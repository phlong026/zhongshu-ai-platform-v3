-- Requires restoring the B backup if AREA_FULL business records exist; never discard them silently.
DO $$ BEGIN IF EXISTS(SELECT 1 FROM fr_commission_submission WHERE mode='AREA_FULL' OR attachment_asset_id IS NOT NULL)
 OR EXISTS(SELECT 1 FROM fr_design_pricing_policy)
 THEN RAISE EXCEPTION 'New pricing/attachment records exist; restore isolated B backup'; END IF; END $$;
ALTER TABLE fr_commission_submission DROP CONSTRAINT ck_fr_commission_submission_amount;
ALTER TABLE fr_commission_submission ADD CONSTRAINT ck_fr_commission_submission_amount CHECK(first_cents>0 AND total_cents>first_cents);
ALTER TABLE fr_commission_submission DROP COLUMN mode, DROP COLUMN rate_cents, DROP COLUMN pricing_version, DROP COLUMN attachment_asset_id;
DROP TABLE fr_design_pricing_policy;
