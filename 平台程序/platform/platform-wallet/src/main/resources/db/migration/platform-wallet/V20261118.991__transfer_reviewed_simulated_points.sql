-- Review is tied to the original receipt and an unmodified append-only ledger.
LOCK TABLE org_point_account, org_point_ledger, org_point_simulation_review IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM org_point_ledger s
        LEFT JOIN org_point_simulation_review d ON d.source_ledger_id=s.id
        LEFT JOIN org_point_account a ON a.org_id=s.org_id
        WHERE s.type='SIMULATED_PACKAGE' AND (
            d.source_ledger_id IS NULL OR d.approved_transfer > s.available_delta
            OR a.balance IS DISTINCT FROM d.reviewed_balance
            OR d.reviewed_ledger_count <> (SELECT count(*) FROM org_point_ledger l WHERE l.org_id=s.org_id)
        )
    ) THEN
        RAISE EXCEPTION 'Historical simulated packages require current per-receipt finance evidence; see simulated-point-inventory.sql';
    END IF;
    IF EXISTS (
        SELECT 1 FROM org_point_ledger r
        WHERE r.type='RESERVE' AND EXISTS (SELECT 1 FROM org_point_ledger s WHERE s.org_id=r.org_id AND s.type='SIMULATED_PACKAGE')
          AND NOT EXISTS (SELECT 1 FROM org_point_ledger t WHERE t.operation_id=r.operation_id AND t.type IN ('SETTLE','RELEASE'))
    ) THEN
        RAISE EXCEPTION 'Outstanding task reservations must be settled or released before reviewing historical test points';
    END IF;
    IF EXISTS (
        SELECT 1 FROM org_point_ledger s JOIN org_point_simulation_review d ON d.source_ledger_id=s.id
        JOIN org_point_account a ON a.org_id=s.org_id WHERE s.type='SIMULATED_PACKAGE'
        GROUP BY s.org_id,a.balance HAVING sum(d.approved_transfer) > a.balance
    ) THEN RAISE EXCEPTION 'Approved transfers exceed the available balance'; END IF;
END $$;

INSERT INTO org_point_simulated_purchase(id,tenant_id,org_id,biz_key,price_fen,test_points,receipt,create_time)
SELECT s.id,split_part(s.biz_key,':',2)::bigint,s.org_id,s.biz_key,(s.price_snapshot->>'priceFen')::bigint,
       d.approved_transfer,s.price_snapshot,s.create_time
FROM org_point_ledger s JOIN org_point_simulation_review d ON d.source_ledger_id=s.id
WHERE s.type='SIMULATED_PACKAGE';

UPDATE org_point_account a SET balance=a.balance-s.points,version=a.version+1,update_time=now()
FROM (SELECT l.org_id,sum(d.approved_transfer) AS points FROM org_point_ledger l
      JOIN org_point_simulation_review d ON d.source_ledger_id=l.id
      WHERE l.type='SIMULATED_PACKAGE' GROUP BY l.org_id) s
WHERE a.org_id=s.org_id AND s.points>0;

INSERT INTO org_point_ledger(id,org_id,biz_key,operation_time,type,available_delta,ref_type,ref_id,note)
SELECT -l.id,l.org_id,'isolate:'||l.biz_key,now(),'SIMULATED_PACKAGE_TRANSFER',-d.approved_transfer,'SIMULATED_PACKAGE',l.id,'按财务确认转入测试账簿'
FROM org_point_ledger l JOIN org_point_simulation_review d ON d.source_ledger_id=l.id
WHERE l.type='SIMULATED_PACKAGE' AND d.approved_transfer>0;
