-- Application rollback may retain this additive schema. Never erase independent records to force rollback.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM budget_draft) OR EXISTS (SELECT 1 FROM budget_estimate WHERE draft_id IS NOT NULL OR project_id IS NULL) THEN
        RAISE EXCEPTION '无法回滚独立预算结构：已有草稿或独立预算事实（含逻辑删除）。请保留扩展结构、回滚应用，不得删除记录以通过回滚。';
    END IF;
END $$;
ALTER TABLE budget_estimate DROP CONSTRAINT ck_budget_estimate_origin;
DROP INDEX idx_budget_estimate_draft;
ALTER TABLE budget_estimate DROP COLUMN draft_id;
ALTER TABLE budget_estimate ALTER COLUMN project_id SET NOT NULL;
DROP TABLE budget_draft;
