-- 仅在停止B流量且完成备份后使用；保留已产生的客户预算版本，业务数据回退需恢复B库备份。
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM fr_sale_quote WHERE budget_scheme_id IS NOT NULL OR budget_version IS NOT NULL) THEN
        RAISE EXCEPTION 'Quote budget links exist; restore isolated B backup for downgrade';
    END IF;
END $$;
DROP INDEX uk_fr_sale_quote_budget_basis;
ALTER TABLE fr_sale_quote DROP CONSTRAINT fr_sale_quote_budget_basis_check;
ALTER TABLE fr_sale_quote DROP COLUMN budget_scheme_id;
ALTER TABLE fr_sale_quote DROP COLUMN budget_version;
