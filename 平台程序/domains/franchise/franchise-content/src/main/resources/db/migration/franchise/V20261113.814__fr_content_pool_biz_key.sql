-- 24 号任务书批 A/B 配套（评审 P1-4）：内容任务记录公司池预扣键，终态后结算失败可幂等重试
ALTER TABLE fr_content_task ADD COLUMN pool_biz_key VARCHAR(160);

CREATE INDEX idx_fct_pool_biz_key ON fr_content_task (pool_biz_key)
    WHERE pool_biz_key IS NOT NULL;
