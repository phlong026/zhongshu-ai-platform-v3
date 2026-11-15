-- 前置：先停止 B 流量，备份数据库；存在正式成果时先恢复隔离库备份，不删除业务资料强行降级。
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM fr_asset WHERE asset_type='DESIGN_DELIVERABLE') THEN
        RAISE EXCEPTION 'DESIGN_DELIVERABLE exists; restore B backup for downgrade';
    END IF;
END $$;
DELETE FROM fr_asset_download_ticket WHERE scope_commission_id IS NOT NULL;
ALTER TABLE fr_asset_download_ticket DROP CONSTRAINT fr_asset_download_scope_check;
ALTER TABLE fr_asset_download_ticket DROP COLUMN owner_org_id;
ALTER TABLE fr_asset_download_ticket DROP COLUMN scope_commission_id;
ALTER TABLE fr_asset_download_ticket DROP COLUMN scope_dispatch_seq;
ALTER TABLE fr_asset_download_ticket DROP COLUMN scope_slot;
ALTER TABLE fr_asset DROP CONSTRAINT fr_asset_asset_type_check;
ALTER TABLE fr_asset ADD CONSTRAINT fr_asset_asset_type_check CHECK (
    asset_type IN ('FIN_VOUCHER','ENG_DRAWING','ENG_SITE_PHOTO','CONTRACT_ATTACHMENT','CONTENT_ASSET','DESIGN_REFERENCE')
);
