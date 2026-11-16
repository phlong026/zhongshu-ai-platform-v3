DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM fr_content_persona WHERE (expression_style IS NOT NULL AND expression_style IS DISTINCT FROM differentiator)
        OR audience_needs IS NOT NULL OR factual_background IS NOT NULL
        OR sample_script IS NOT NULL OR forbidden_claims IS NOT NULL) THEN
        RAISE EXCEPTION 'IP定位资料已存在，禁止回滚923';
    END IF;
END $$;
ALTER TABLE fr_content_persona
    DROP COLUMN expression_style, DROP COLUMN audience_needs, DROP COLUMN factual_background,
    DROP COLUMN sample_script, DROP COLUMN forbidden_claims;
