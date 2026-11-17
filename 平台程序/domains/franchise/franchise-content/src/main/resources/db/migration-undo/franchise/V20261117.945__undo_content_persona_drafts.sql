-- 只允许空表回滚，禁止清除已自动保存的用户内容或确认请求的幂等记录。
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM fr_content_persona_draft) THEN
        RAISE EXCEPTION '人物定位草稿或保存回执已存在，禁止回滚945';
    END IF;
END $$;
DROP TABLE fr_content_persona_draft;
