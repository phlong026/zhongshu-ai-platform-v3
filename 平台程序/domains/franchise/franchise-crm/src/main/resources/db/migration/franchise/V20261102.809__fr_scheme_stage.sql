-- V20261102.809：fr_customer_scheme 方案升级列（B2 板块 B / B2 任务）
-- AI 结果自动落 reference（板块 A 回写默认值），业务员经交易流单向升级 formal。

ALTER TABLE fr_customer_scheme ADD COLUMN stage VARCHAR(16) NOT NULL DEFAULT 'reference';
ALTER TABLE fr_customer_scheme ADD CONSTRAINT ck_fr_customer_scheme_stage
    CHECK (stage IN ('reference', 'formal'));
