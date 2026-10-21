-- 回滚 V20261010.404：删除 attempt 上记录的提示词与模型名
--
-- 有损回滚：已经记录的提示词与模型名会随列一起丢失（它们不是业务主数据，但运营复盘会用到）。
-- 需要保留时请先导出：SELECT job_id, attempt_no, provider_model, prompt_text FROM ai_job_attempt WHERE prompt_text IS NOT NULL;
ALTER TABLE ai_job_attempt DROP CONSTRAINT IF EXISTS ck_ai_job_attempt_prompt_len;
ALTER TABLE ai_job_attempt
    DROP COLUMN IF EXISTS prompt_text,
    DROP COLUMN IF EXISTS provider_model;
