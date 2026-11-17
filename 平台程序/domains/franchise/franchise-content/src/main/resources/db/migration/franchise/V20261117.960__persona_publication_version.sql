ALTER TABLE fr_content_persona ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE fr_content_persona_draft ADD COLUMN base_persona_version BIGINT;
-- Existing drafts intentionally keep NULL: their original official revision cannot be inferred.
