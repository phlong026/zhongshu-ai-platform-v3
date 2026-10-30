-- V20261010.404：需求四二期——按「尝试」记录 plan 阶段产出的最终提示词与所用模型
--
-- 提示词原先只存在 ai-runtime 的本地 journal 里，后台无法复盘「这条记录用了什么提示词、什么模型」。
-- 挂在 attempt 上而不是 job 上：重试可能产出不同的提示词，详情展示最近一次有记录的尝试。
-- 两列均可空：历史任务没有记录，runtime 未升级时也不回传，二者都不影响任何既有流程。
ALTER TABLE ai_job_attempt
    ADD COLUMN prompt_text    TEXT        NULL,
    ADD COLUMN provider_model VARCHAR(64) NULL;

-- 与 runtime 侧 8000 字上限一致，数据库再兜一层
ALTER TABLE ai_job_attempt
    ADD CONSTRAINT ck_ai_job_attempt_prompt_len CHECK (prompt_text IS NULL OR char_length(prompt_text) <= 8000);
