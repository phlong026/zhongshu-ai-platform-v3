-- 有新设计来源账时禁止丢弃关系；应恢复隔离B的部署前数据库备份。
DO $$ BEGIN
    IF EXISTS(SELECT 1 FROM fr_fin_payable WHERE source_type='design')
       OR EXISTS(SELECT 1 FROM fr_fin_receipt WHERE origin_payment_id IS NOT NULL) THEN
        RAISE EXCEPTION 'restore pre-deployment backup: design finance data exists';
    END IF;
END $$;
DROP TRIGGER trg_fr_fin_design_source ON fr_fin_payable;
DROP FUNCTION fr_fin_design_source_immutable();
DROP INDEX idx_fr_fin_receipt_origin_payment;
DROP INDEX idx_fr_fin_payment_org_transaction;
ALTER TABLE fr_fin_receipt DROP COLUMN origin_payment_id;
DROP INDEX uk_fr_fin_payable_source;
ALTER TABLE fr_fin_payable DROP COLUMN source_type, DROP COLUMN source_ref, DROP COLUMN customer_id, DROP COLUMN design_org_id;
