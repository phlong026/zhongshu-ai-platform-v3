-- 仅用于尚未开放 B 写入的隔离演练回滚。已有资产/票据时拒绝删除，须先执行获批的数据保留恢复方案。
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM fr_asset) THEN
        RAISE EXCEPTION 'B asset data exists; preserve objects and metadata before rollback';
    END IF;
END $$;
DROP TABLE fr_asset_download_ticket;
DROP TABLE fr_asset_upload_ticket;
DROP TABLE fr_asset;
