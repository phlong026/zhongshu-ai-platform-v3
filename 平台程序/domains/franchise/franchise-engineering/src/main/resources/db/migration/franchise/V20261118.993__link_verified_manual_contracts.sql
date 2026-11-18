-- 历史主工程只补全明确的关联。已独立收过工程款、同一客户多工程或历史约定不一致时保留原账。
WITH candidates AS (
    SELECT e.id AS project_id,c.id AS customer_id,s.id AS contract_id,e.company_id,
           count(*) OVER (PARTITION BY e.company_id,s.id) AS project_count
      FROM fr_eng_project e
      JOIN fr_customer c ON c.company_id=e.company_id AND c.phone=e.phone AND c.name=e.customer_name
      JOIN fr_sale_contract s ON s.company_id=c.company_id AND s.customer_id=c.id
                             AND s.status='SIGNED' AND NOT s.deleted
     WHERE e.source_type='MANUAL' AND e.parent_project_id IS NULL
       AND e.sale_contract_id IS NULL AND (e.customer_id IS NULL OR e.customer_id=c.id)
), verified AS (
    SELECT v.* FROM candidates v
      JOIN fr_eng_project e ON e.id=v.project_id
      JOIN fr_sale_contract s ON s.id=v.contract_id
     WHERE v.project_count=1
       AND NOT EXISTS(SELECT 1 FROM fr_eng_project other
                       WHERE other.company_id=v.company_id AND other.sale_contract_id=v.contract_id)
       AND NOT EXISTS(SELECT 1 FROM fr_eng_collection ec WHERE ec.company_id=v.company_id AND ec.project_id=e.id)
       AND (
           (e.prepared_at IS NULL AND e.actual_start_at IS NULL AND e.status='PENDING_START'
            AND e.contract_total_cents IS NULL AND s.signed_date<=e.create_time::date)
           OR (e.contract_date=s.signed_date AND e.contract_total_cents=s.total_cents
            AND EXISTS(SELECT 1 FROM fr_eng_collection_term t WHERE t.company_id=e.company_id AND t.project_id=e.id)
            AND NOT EXISTS (
                (SELECT t.stage,t.amount_cents FROM fr_eng_collection_term t WHERE t.company_id=e.company_id AND t.project_id=e.id
                 EXCEPT SELECT f.stage,f.amount_cents FROM fr_sale_payment_stage f WHERE f.company_id=e.company_id AND f.contract_id=s.id AND NOT f.deleted)
                UNION ALL
                (SELECT f.stage,f.amount_cents FROM fr_sale_payment_stage f WHERE f.company_id=e.company_id AND f.contract_id=s.id AND NOT f.deleted
                 EXCEPT SELECT t.stage,t.amount_cents FROM fr_eng_collection_term t WHERE t.company_id=e.company_id AND t.project_id=e.id)
            ))
       )
)
UPDATE fr_eng_project e SET customer_id=v.customer_id,sale_contract_id=v.contract_id,update_time=now()
  FROM verified v WHERE e.id=v.project_id AND e.company_id=v.company_id AND e.sale_contract_id IS NULL;
-- 业务后签约时，关联已经通过完整手机号及姓名核对的客户。施工准备后由工程核对三期再固化。
CREATE OR REPLACE FUNCTION fr_eng_link_new_signed_contract() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE candidate bigint;
BEGIN
    IF NEW.status<>'SIGNED' OR NEW.deleted THEN RETURN NULL; END IF;
    IF TG_OP='UPDATE' AND OLD.status='SIGNED' THEN RETURN NULL; END IF;
    PERFORM id FROM fr_customer WHERE id=NEW.customer_id AND company_id=NEW.company_id FOR UPDATE;
    IF EXISTS(SELECT 1 FROM fr_eng_project WHERE company_id=NEW.company_id AND sale_contract_id=NEW.id) THEN
        RETURN NULL;
    END IF;
    SELECT min(id) INTO candidate FROM fr_eng_project
      WHERE company_id=NEW.company_id AND customer_id=NEW.customer_id AND sale_contract_id IS NULL
        AND source_type='MANUAL' AND parent_project_id IS NULL AND status='PENDING_START'
      HAVING count(*)=1;
    IF candidate IS NOT NULL THEN
        UPDATE fr_eng_project e SET sale_contract_id=NEW.id,update_time=now()
          WHERE e.id=candidate AND e.company_id=NEW.company_id AND e.sale_contract_id IS NULL
            AND e.prepared_at IS NULL AND e.actual_start_at IS NULL
            AND NOT EXISTS(SELECT 1 FROM fr_eng_collection ec WHERE ec.company_id=e.company_id AND ec.project_id=e.id);
    END IF;
    RETURN NULL;
END $$;

DROP TRIGGER IF EXISTS fr_eng_link_signed_contract ON fr_sale_contract;
CREATE TRIGGER fr_eng_link_signed_contract AFTER INSERT OR UPDATE OF status ON fr_sale_contract
    FOR EACH ROW EXECUTE FUNCTION fr_eng_link_new_signed_contract();
