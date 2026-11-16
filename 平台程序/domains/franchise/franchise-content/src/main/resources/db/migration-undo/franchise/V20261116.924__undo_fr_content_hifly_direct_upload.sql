DO $$ BEGIN
    LOCK TABLE fr_content_hifly_upload,fr_content_digital_clone IN ACCESS EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM fr_content_hifly_upload) OR EXISTS (SELECT 1 FROM fr_content_digital_clone WHERE source_upload_id IS NOT NULL) THEN
        RAISE EXCEPTION '飞影直传或制作事实已存在，禁止回滚924';
    END IF;
    ALTER TABLE fr_content_digital_clone DROP CONSTRAINT ck_fr_clone_one_source;
    ALTER TABLE fr_content_digital_clone DROP COLUMN source_upload_id;
    ALTER TABLE fr_content_digital_clone ALTER COLUMN source_asset_id SET NOT NULL;
    DROP TABLE fr_content_hifly_upload;
END $$;
