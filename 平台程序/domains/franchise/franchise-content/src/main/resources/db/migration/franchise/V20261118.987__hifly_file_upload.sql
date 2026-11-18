ALTER TABLE fr_content_hifly_upload
    ALTER COLUMN size_bytes DROP NOT NULL,
    ALTER COLUMN declared_sha256 DROP NOT NULL,
    DROP CONSTRAINT fr_content_hifly_upload_status_check,
    ADD CONSTRAINT fr_content_hifly_upload_status_check CHECK (status IN ('ISSUED','AWAITING_FILE','UPLOADED')),
    ADD CONSTRAINT fr_content_hifly_upload_file_metadata CHECK (
        (status='AWAITING_FILE' AND size_bytes IS NULL AND declared_sha256 IS NULL)
        OR (status<>'AWAITING_FILE' AND size_bytes IS NOT NULL AND declared_sha256 IS NOT NULL)
    );
