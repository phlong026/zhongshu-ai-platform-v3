ALTER TABLE fr_content_video_draft ADD COLUMN draft_key VARCHAR(32) NOT NULL DEFAULT 'new';
ALTER TABLE fr_content_video_draft ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE fr_content_video_draft DROP CONSTRAINT uk_fr_content_video_draft_author;
ALTER TABLE fr_content_video_draft ADD CONSTRAINT uk_content_video_draft_scope UNIQUE(company_id,author_user_id,draft_key);
