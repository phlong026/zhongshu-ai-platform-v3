ALTER TABLE fr_content_draft
    ADD COLUMN trashed_at TIMESTAMPTZ,
    ADD COLUMN library_revision BIGINT NOT NULL DEFAULT 0 CHECK (library_revision >= 0);
