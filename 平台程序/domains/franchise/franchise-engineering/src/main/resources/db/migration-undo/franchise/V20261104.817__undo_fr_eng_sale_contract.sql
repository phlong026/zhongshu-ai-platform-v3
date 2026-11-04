-- 回滚 V20261104.817：工程档案统一合同关联列
DROP INDEX IF EXISTS idx_fr_eng_project_sale_contract;
ALTER TABLE fr_eng_project DROP COLUMN IF EXISTS sale_contract_id;
