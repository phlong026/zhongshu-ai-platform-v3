-- Keep legacy direct-upload records valid; new imports bind only server-verified CONTENT_ASSET bytes.
ALTER TABLE fr_content_hifly_upload
    ADD COLUMN source_asset_id BIGINT,
    ADD COLUMN import_token VARCHAR(64),
    ADD COLUMN import_until TIMESTAMPTZ;
CREATE UNIQUE INDEX uq_fr_hifly_asset_import
    ON fr_content_hifly_upload(company_id,author_user_id,author_post_id,persona_id,kind,source_asset_id)
    WHERE source_asset_id IS NOT NULL;
