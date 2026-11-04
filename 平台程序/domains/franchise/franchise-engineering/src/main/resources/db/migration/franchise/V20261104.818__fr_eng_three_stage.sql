-- V20261104.818：M07-T06/I05 工程收款三期适配——与销售域 M06-T04 枚举对齐
-- （SIGN_AFTER 签约后 / BASE_DONE 基础完成后 / CAP_DONE 封顶后）。
-- 存量两期核实映射：START→SIGN_AFTER、TOPPING→CAP_DONE（不复制流水）；
-- BASE_DONE 缺失期补建 amount=0（未约定留待合同变更，不发明固定比例）。
-- 0 元期行需放宽金额 CHECK；purpose 增「基础完成款」。

ALTER TABLE fr_eng_collection_term DROP CONSTRAINT ck_fr_eng_collection_term_stage;
ALTER TABLE fr_eng_collection_term ADD CONSTRAINT ck_fr_eng_collection_term_stage
    CHECK (stage IN ('START', 'TOPPING', 'SIGN_AFTER', 'BASE_DONE', 'CAP_DONE'));
ALTER TABLE fr_eng_collection_term DROP CONSTRAINT ck_fr_eng_collection_term_amount;
ALTER TABLE fr_eng_collection_term ADD CONSTRAINT ck_fr_eng_collection_term_amount
    CHECK (amount_cents >= 0);
ALTER TABLE fr_eng_collection DROP CONSTRAINT ck_fr_eng_collection_stage;
ALTER TABLE fr_eng_collection ADD CONSTRAINT ck_fr_eng_collection_stage
    CHECK (stage IN ('START', 'TOPPING', 'SIGN_AFTER', 'BASE_DONE', 'CAP_DONE'));
ALTER TABLE fr_eng_collection DROP CONSTRAINT ck_fr_eng_collection_purpose;
ALTER TABLE fr_eng_collection ADD CONSTRAINT ck_fr_eng_collection_purpose
    CHECK (purpose IN ('工程预收款', '工程尾款', '基础完成款'));

UPDATE fr_eng_collection_term SET stage = 'SIGN_AFTER' WHERE stage = 'START';
UPDATE fr_eng_collection_term SET stage = 'CAP_DONE' WHERE stage = 'TOPPING';
UPDATE fr_eng_collection SET stage = 'SIGN_AFTER' WHERE stage = 'START';
UPDATE fr_eng_collection SET stage = 'CAP_DONE' WHERE stage = 'TOPPING';

-- fr_eng_followup（催收回访）stage CHECK 同步扩展+存量映射（R1 P1-4：遗漏读取方/写入方）
ALTER TABLE fr_eng_followup DROP CONSTRAINT ck_fr_eng_followup_stage;
ALTER TABLE fr_eng_followup ADD CONSTRAINT ck_fr_eng_followup_stage
    CHECK (stage IS NULL OR stage IN ('START', 'TOPPING', 'SIGN_AFTER', 'BASE_DONE', 'CAP_DONE'));
UPDATE fr_eng_followup SET stage = 'SIGN_AFTER' WHERE stage = 'START';
UPDATE fr_eng_followup SET stage = 'CAP_DONE' WHERE stage = 'TOPPING';

-- BASE_DONE 缺失期补建（固定段 id 930...；数量=项目数有限）
INSERT INTO fr_eng_collection_term (id, company_id, project_id, stage, amount_cents, create_time)
SELECT 930000000000 + row_number() OVER (ORDER BY p.id), p.company_id, p.id, 'BASE_DONE', 0, now()
FROM fr_eng_project p
WHERE NOT EXISTS (SELECT 1 FROM fr_eng_collection_term t
                  WHERE t.project_id = p.id AND t.stage = 'BASE_DONE');
