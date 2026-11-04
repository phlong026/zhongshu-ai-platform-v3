-- V20261104.817：M06-T03 统一合同关联——工程档案携带 sale_contract_id（统一建房合同；
-- 客户无活跃合同时为 NULL 兼容，随合同变更不回写——验收「关联统一 contractId」）。
ALTER TABLE fr_eng_project
    ADD COLUMN IF NOT EXISTS sale_contract_id BIGINT NULL;
CREATE INDEX IF NOT EXISTS idx_fr_eng_project_sale_contract
    ON fr_eng_project (company_id, sale_contract_id) WHERE sale_contract_id IS NOT NULL;
