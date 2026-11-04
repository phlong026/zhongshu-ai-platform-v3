-- 回滚 V20261104.818：工程收款三期适配
-- R8 教训（批次#21）：雪花 id（19 位）恒大于 9.3e11 固定段——必须精确区间限定迁移补建行。
-- R1 P1-7 预检：运行期新增的 BASE_DONE 期 term/实收（id 为雪花，不在固定段）回滚前须先人工
-- 导出（SELECT * FROM fr_eng_collection_term t LEFT JOIN fr_eng_collection c ON ... WHERE t.stage='BASE_DONE'
-- OR c.stage='BASE_DONE'）并确认归属——本脚本不静默删除业务数据。
DELETE FROM fr_eng_collection_term WHERE stage = 'BASE_DONE'
    AND id >= 930000000000 AND id < 930100000000;
UPDATE fr_eng_collection_term SET stage = 'START' WHERE stage = 'SIGN_AFTER';
UPDATE fr_eng_collection_term SET stage = 'TOPPING' WHERE stage = 'CAP_DONE';
UPDATE fr_eng_collection SET stage = 'START' WHERE stage = 'SIGN_AFTER';
UPDATE fr_eng_collection SET stage = 'TOPPING' WHERE stage = 'CAP_DONE'; -- R1 P1-7：方向修正（原写 TOPPING→CAP_DONE 反向）
ALTER TABLE fr_eng_collection DROP CONSTRAINT ck_fr_eng_collection_purpose;
ALTER TABLE fr_eng_collection ADD CONSTRAINT ck_fr_eng_collection_purpose
    CHECK (purpose IN ('工程预收款', '工程尾款'));
ALTER TABLE fr_eng_collection DROP CONSTRAINT ck_fr_eng_collection_stage;
ALTER TABLE fr_eng_collection ADD CONSTRAINT ck_fr_eng_collection_stage
    CHECK (stage IN ('START', 'TOPPING'));
ALTER TABLE fr_eng_collection_term DROP CONSTRAINT ck_fr_eng_collection_term_amount;
ALTER TABLE fr_eng_collection_term ADD CONSTRAINT ck_fr_eng_collection_term_amount
    CHECK (amount_cents > 0);
ALTER TABLE fr_eng_collection_term DROP CONSTRAINT ck_fr_eng_collection_term_stage;
ALTER TABLE fr_eng_collection_term ADD CONSTRAINT ck_fr_eng_collection_term_stage
    CHECK (stage IN ('START', 'TOPPING'));

-- R1 P1-4：followup 逆映射与 CHECK 恢复（forward 有同表变更，undo 须对称）
UPDATE fr_eng_followup SET stage = 'START' WHERE stage = 'SIGN_AFTER';
UPDATE fr_eng_followup SET stage = 'TOPPING' WHERE stage = 'CAP_DONE';
ALTER TABLE fr_eng_followup DROP CONSTRAINT ck_fr_eng_followup_stage;
ALTER TABLE fr_eng_followup ADD CONSTRAINT ck_fr_eng_followup_stage
    CHECK (stage IS NULL OR stage IN ('START', 'TOPPING'));