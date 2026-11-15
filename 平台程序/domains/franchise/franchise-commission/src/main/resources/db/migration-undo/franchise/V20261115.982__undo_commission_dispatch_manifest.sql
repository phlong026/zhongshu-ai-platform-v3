DO $$ BEGIN IF EXISTS(SELECT 1 FROM fr_commission_dispatch_manifest)
 OR EXISTS(SELECT 1 FROM fr_commission_handover WHERE confirmation_asset_id IS NOT NULL)
 THEN RAISE EXCEPTION 'Dispatch manifests exist; restore isolated B backup'; END IF; END $$;
ALTER TABLE fr_commission_handover DROP COLUMN confirmation_asset_id;
DROP TABLE fr_commission_dispatch_manifest;
