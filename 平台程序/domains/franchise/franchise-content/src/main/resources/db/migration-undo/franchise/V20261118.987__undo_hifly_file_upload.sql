-- Any outstanding browser ticket prevents rollback through the NOT NULL constraints.
ALTER TABLE fr_content_hifly_upload
    DROP CONSTRAINT fr_content_hifly_upload_file_metadata,
    DROP CONSTRAINT fr_content_hifly_upload_status_check,
    ALTER COLUMN size_bytes SET NOT NULL,
    ALTER COLUMN declared_sha256 SET NOT NULL,
    ADD CONSTRAINT fr_content_hifly_upload_status_check CHECK (status IN ('ISSUED','UPLOADED'));
