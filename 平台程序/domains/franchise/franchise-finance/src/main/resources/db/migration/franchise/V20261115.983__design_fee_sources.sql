-- 不重写历史手工账；新设计账以不可变委托版本/阶段归属。
ALTER TABLE fr_fin_payable ADD COLUMN source_type VARCHAR(16), ADD COLUMN source_ref VARCHAR(128),
    ADD COLUMN customer_id BIGINT, ADD COLUMN design_org_id BIGINT;
CREATE UNIQUE INDEX uk_fr_fin_payable_source ON fr_fin_payable(company_id, source_type, source_ref)
    WHERE source_type IS NOT NULL AND source_ref IS NOT NULL;
ALTER TABLE fr_fin_receipt ADD COLUMN origin_payment_id BIGINT;
CREATE INDEX idx_fr_fin_receipt_origin_payment ON fr_fin_receipt(origin_payment_id);
CREATE INDEX idx_fr_fin_payment_org_transaction ON fr_fin_payment(company_id,transaction_no)
    WHERE transaction_no IS NOT NULL;
CREATE FUNCTION fr_fin_design_source_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.source_type = 'design' AND (NEW.source_type,NEW.source_ref,NEW.customer_id,NEW.company_id,NEW.total_cents,NEW.stage,NEW.design_org_id)
        IS DISTINCT FROM (OLD.source_type,OLD.source_ref,OLD.customer_id,OLD.company_id,OLD.total_cents,OLD.stage,OLD.design_org_id) THEN
        RAISE EXCEPTION 'design payable source is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_fr_fin_design_source BEFORE UPDATE ON fr_fin_payable
    FOR EACH ROW EXECUTE FUNCTION fr_fin_design_source_immutable();
