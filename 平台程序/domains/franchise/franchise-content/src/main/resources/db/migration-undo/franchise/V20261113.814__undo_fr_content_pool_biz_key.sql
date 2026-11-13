-- V20261113.814 回滚：移除内容任务的池预扣键
DROP INDEX IF EXISTS idx_fct_pool_biz_key;
ALTER TABLE fr_content_task DROP COLUMN IF EXISTS pool_biz_key;
