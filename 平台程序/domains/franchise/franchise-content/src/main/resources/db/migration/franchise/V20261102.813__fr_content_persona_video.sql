-- V20261102.813：内容创作获客人设/视频草稿/课程进度三表（阶段 1.5，franchise-content 域）

CREATE TABLE fr_content_persona (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    who VARCHAR(200) NULL, sell VARCHAR(500) NULL, audience VARCHAR(200) NULL, cta VARCHAR(500) NULL,
    ip_type VARCHAR(32) NULL, track VARCHAR(100) NULL, differentiator VARCHAR(500) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    author_user_id BIGINT NOT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(), update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_fr_content_persona PRIMARY KEY (id)
);
CREATE INDEX idx_fr_content_persona_org ON fr_content_persona (company_id) WHERE deleted = FALSE;

-- 视频草稿一人一份（v3 ai-video 蓝本：frames/reference 两模式 + 首尾帧/参考素材）。
-- 注：任务书 §四 写的列名 references 为 PG 保留字，落库列名定为 reference_media（语义不变）。
CREATE TABLE fr_content_video_draft (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, author_user_id BIGINT NOT NULL,
    mode VARCHAR(16) NOT NULL DEFAULT 'frames',
    description TEXT NULL,
    first_frame_asset_id BIGINT NULL, last_frame_asset_id BIGINT NULL,
    parameters JSONB NULL, reference_media JSONB NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(), update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_content_video_draft PRIMARY KEY (id),
    CONSTRAINT ck_fr_content_video_draft_mode CHECK (mode IN ('frames','reference')),
    CONSTRAINT uk_fr_content_video_draft_author UNIQUE (author_user_id)
);

CREATE TABLE fr_content_course_progress (
    id BIGINT NOT NULL, company_id BIGINT NOT NULL, author_user_id BIGINT NOT NULL,
    course_id VARCHAR(64) NOT NULL, chapter_index INT NOT NULL, completed_at TIMESTAMPTZ NOT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fr_content_course_progress PRIMARY KEY (id),
    CONSTRAINT uk_fr_content_course_progress UNIQUE (author_user_id, course_id, chapter_index),
    CONSTRAINT ck_fr_content_course_chapter CHECK (chapter_index >= 0)
);
