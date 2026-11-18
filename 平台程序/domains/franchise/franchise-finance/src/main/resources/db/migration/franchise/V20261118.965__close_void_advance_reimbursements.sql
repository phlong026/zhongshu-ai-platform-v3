ALTER TABLE fr_fin_payable DROP CONSTRAINT fr_fin_payable_status_check;
ALTER TABLE fr_fin_payable ADD CONSTRAINT fr_fin_payable_status_check
    CHECK (status IN ('进行中','已结清') OR (status = '已作废' AND source_type IS NOT DISTINCT FROM 'reimbursement'));

UPDATE fr_fin_payable q SET status='已作废', update_time=now()
FROM fr_fin_payment m
WHERE q.source_type='reimbursement' AND q.source_ref=m.id::text AND q.company_id=m.company_id
  AND m.funding_type='EMPLOYEE' AND m.status='已作废' AND q.status<>'已作废'
  AND NOT EXISTS (
      SELECT 1 FROM fr_fin_payment p WHERE p.company_id=q.company_id AND p.payable_id=q.id AND p.status='已核实'
  );
