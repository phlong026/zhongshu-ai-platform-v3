-- 状态与原单同事务维护。锁住应付行再读流水，退款、作废及重新关联均可重新打开应付。
CREATE OR REPLACE FUNCTION fr_fin_refresh_payable(p_org bigint, p_id bigint) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    bill fr_fin_payable%ROWTYPE;
    due numeric;
    net numeric;
    new_status text;
BEGIN
    SELECT * INTO bill FROM fr_fin_payable WHERE company_id=p_org AND id=p_id AND NOT deleted FOR UPDATE;
    IF NOT FOUND OR bill.status='已作废' THEN RETURN; END IF;
    due := bill.total_cents;
    SELECT COALESCE(sum(m.amount_cents - greatest(COALESCE(m.pending_link_cents,0),
               COALESCE((SELECT sum(f.amount_cents) FROM fr_fin_refund f
                         WHERE f.company_id=p_org AND f.payment_id=m.id AND f.refund_direction='expense'
                           AND f.status='已核实' AND NOT f.deleted),0))),0)
      INTO net FROM fr_fin_payment m
      WHERE m.company_id=p_org AND m.payable_id=p_id AND m.status='已核实' AND NOT m.deleted;
    IF bill.source_type='reimbursement' THEN
        SELECT m.amount_cents - COALESCE((SELECT sum(f.amount_cents) FROM fr_fin_refund f
                 WHERE f.company_id=p_org AND f.payment_id=m.id AND f.funding_type='EMPLOYEE'
                   AND f.status='已核实' AND NOT f.deleted),0)
          INTO due FROM fr_fin_payment m WHERE m.company_id=p_org AND m.id::text=bill.source_ref
                              AND m.status='已核实' AND NOT m.deleted;
        IF NOT FOUND THEN RETURN; END IF;
        SELECT net-COALESCE(sum(r.amount_cents),0) INTO net FROM fr_fin_receipt r
          WHERE r.company_id=p_org AND r.source_ref='employee-recovery:'||p_id
            AND r.status='已登记' AND NOT r.deleted;
    END IF;
    new_status := CASE WHEN net>=due THEN '已结清' ELSE '进行中' END;
    IF bill.status IS DISTINCT FROM new_status THEN
        UPDATE fr_fin_payable SET status=new_status,update_time=now() WHERE id=p_id AND company_id=p_org;
    END IF;
END $$;

-- 所有现有写入口共享这一规则，包含款项重新关联、支出退回、员工退款及公司收回垫付款。
CREATE OR REPLACE FUNCTION fr_fin_refresh_payment_bills(p_org bigint, p_payment bigint, p_bill bigint) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE affected bigint;
BEGIN
    FOR affected IN
        SELECT p_bill WHERE p_bill IS NOT NULL
        UNION SELECT q.id FROM fr_fin_payable q
              WHERE q.company_id=p_org AND q.source_type='reimbursement' AND q.source_ref=p_payment::text
        ORDER BY 1
    LOOP
        PERFORM fr_fin_refresh_payable(p_org,affected);
    END LOOP;
END $$;

CREATE OR REPLACE FUNCTION fr_fin_payable_fact_changed() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    before_row jsonb;
    after_row jsonb;
    fact record;
    bill_id bigint;
BEGIN
    IF TG_OP<>'INSERT' THEN before_row:=to_jsonb(OLD); END IF;
    IF TG_OP<>'DELETE' THEN after_row:=to_jsonb(NEW); END IF;
    IF TG_TABLE_NAME='fr_fin_payment' THEN
        FOR fact IN SELECT DISTINCT (r->>'company_id')::bigint org,(r->>'id')::bigint payment,(r->>'payable_id')::bigint bill
                    FROM (VALUES(before_row),(after_row)) v(r) WHERE r IS NOT NULL ORDER BY org,bill
        LOOP
            PERFORM fr_fin_refresh_payment_bills(fact.org,fact.payment,fact.bill);
        END LOOP;
    ELSIF TG_TABLE_NAME='fr_fin_refund' THEN
        FOR fact IN SELECT DISTINCT (r->>'company_id')::bigint org,(r->>'payment_id')::bigint payment
                    FROM (VALUES(before_row),(after_row)) v(r) WHERE r->>'payment_id' IS NOT NULL ORDER BY org,payment
        LOOP
            SELECT payable_id INTO bill_id FROM fr_fin_payment WHERE company_id=fact.org AND id=fact.payment;
            PERFORM fr_fin_refresh_payment_bills(fact.org,fact.payment,bill_id);
        END LOOP;
    ELSIF TG_TABLE_NAME='fr_fin_receipt' THEN
        FOR fact IN SELECT DISTINCT (r->>'company_id')::bigint org,r->>'source_ref' ref
                    FROM (VALUES(before_row),(after_row)) v(r) WHERE r->>'source_ref' LIKE 'employee-recovery:%'
        LOOP
            SELECT id INTO bill_id FROM fr_fin_payable WHERE company_id=fact.org AND source_type='reimbursement'
                AND 'employee-recovery:'||id=fact.ref;
            IF FOUND THEN PERFORM fr_fin_refresh_payable(fact.org,bill_id); END IF;
        END LOOP;
    ELSE
        PERFORM fr_fin_refresh_payable(NEW.company_id,NEW.id);
    END IF;
    RETURN NULL;
END $$;

DROP TRIGGER IF EXISTS fr_payment_settlement ON fr_fin_payment;
CREATE TRIGGER fr_payment_settlement AFTER INSERT OR DELETE OR UPDATE OF
    payable_id,company_id,amount_cents,pending_link_cents,status,deleted ON fr_fin_payment
    FOR EACH ROW EXECUTE FUNCTION fr_fin_payable_fact_changed();
DROP TRIGGER IF EXISTS fr_expense_refund_settlement ON fr_fin_refund;
CREATE TRIGGER fr_expense_refund_settlement AFTER INSERT OR DELETE OR UPDATE OF
    payment_id,company_id,amount_cents,status,refund_direction,funding_type,deleted ON fr_fin_refund
    FOR EACH ROW EXECUTE FUNCTION fr_fin_payable_fact_changed();
DROP TRIGGER IF EXISTS fr_recovery_settlement ON fr_fin_receipt;
CREATE TRIGGER fr_recovery_settlement AFTER INSERT OR DELETE OR UPDATE OF
    source_ref,company_id,amount_cents,status,deleted ON fr_fin_receipt
    FOR EACH ROW EXECUTE FUNCTION fr_fin_payable_fact_changed();
DROP TRIGGER IF EXISTS fr_payable_amount_settlement ON fr_fin_payable;
CREATE TRIGGER fr_payable_amount_settlement AFTER UPDATE OF total_cents ON fr_fin_payable
    FOR EACH ROW WHEN (OLD.total_cents IS DISTINCT FROM NEW.total_cents) EXECUTE FUNCTION fr_fin_payable_fact_changed();

DO $$
DECLARE bill record;
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='platform_app') THEN
        GRANT EXECUTE ON FUNCTION fr_fin_refresh_payable(bigint,bigint),
            fr_fin_refresh_payment_bills(bigint,bigint,bigint),fr_fin_payable_fact_changed() TO platform_app;
    END IF;
    FOR bill IN SELECT q.company_id,q.id FROM fr_fin_payable q WHERE NOT q.deleted AND q.status<>'已作废'
        AND (q.source_type='reimbursement' OR EXISTS(SELECT 1 FROM fr_fin_payment m
                                                    WHERE m.company_id=q.company_id AND m.payable_id=q.id))
        ORDER BY q.company_id,q.id
    LOOP
        PERFORM fr_fin_refresh_payable(bill.company_id,bill.id);
    END LOOP;
END $$;
