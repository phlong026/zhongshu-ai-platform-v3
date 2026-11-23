DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM fr_content_hifly_upload WHERE source_asset_id IS NOT NULL) THEN
        RAISE EXCEPTION '禁止回滚1021：已有规范化素材上传记录';
    END IF;
END $$;
DROP INDEX uq_fr_hifly_asset_import;
ALTER TABLE fr_content_hifly_upload DROP COLUMN source_asset_id, DROP COLUMN import_token, DROP COLUMN import_until;
