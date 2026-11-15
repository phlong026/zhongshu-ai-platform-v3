-- 旧报价保持原样；正常重新保存预算才创建可用于新委托的冻结版本，不在读取中补写。
ALTER TABLE fr_sale_quote ADD COLUMN budget_scheme_id BIGINT REFERENCES fr_customer_scheme(id);
ALTER TABLE fr_sale_quote ADD COLUMN budget_version INTEGER;
ALTER TABLE fr_sale_quote ADD CONSTRAINT fr_sale_quote_budget_basis_check CHECK (
    (budget_scheme_id IS NULL AND budget_version IS NULL)
    OR (budget_scheme_id IS NOT NULL AND budget_version IS NOT NULL AND budget_version > 0)
);
CREATE UNIQUE INDEX uk_fr_sale_quote_budget_basis ON fr_sale_quote(budget_scheme_id) WHERE budget_scheme_id IS NOT NULL;
