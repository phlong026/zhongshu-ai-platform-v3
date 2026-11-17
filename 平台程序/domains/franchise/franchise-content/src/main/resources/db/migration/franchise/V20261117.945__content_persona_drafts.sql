-- 未完成定位独立于组织人物库；工作身份与人员只能由登录上下文确定。
CREATE TABLE fr_content_persona_draft (
    company_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    draft_key VARCHAR(20) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    form JSONB,
    published_revision BIGINT,
    published_persona_id BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, user_id, draft_key),
    CHECK (form IS NULL OR jsonb_typeof(form) = 'object')
);
COMMENT ON TABLE fr_content_persona_draft IS '个人IP定位自动保存草稿；new或人物ID；确认保存后保留版本墓碑防止旧请求复活';
