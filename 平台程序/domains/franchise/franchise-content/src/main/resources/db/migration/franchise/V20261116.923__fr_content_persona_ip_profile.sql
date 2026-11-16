ALTER TABLE fr_content_persona
    ADD COLUMN expression_style VARCHAR(500),
    ADD COLUMN audience_needs VARCHAR(600),
    ADD COLUMN factual_background VARCHAR(2000),
    ADD COLUMN sample_script VARCHAR(2000),
    ADD COLUMN forbidden_claims VARCHAR(600);

-- 旧人物特色作为首次迁移的表达特点；后续允许独立编辑和清空。
UPDATE fr_content_persona SET expression_style = differentiator
WHERE expression_style IS NULL AND differentiator IS NOT NULL;
