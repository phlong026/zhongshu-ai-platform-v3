-- The simulation receipt is the test-points ledger. Formal reservations only consult org_point_account.
CREATE TABLE org_point_simulated_purchase (
    id bigint PRIMARY KEY, tenant_id bigint NOT NULL, org_id bigint NOT NULL,
    biz_key text NOT NULL UNIQUE, price_fen bigint NOT NULL CHECK (price_fen >= 0),
    test_points bigint NOT NULL CHECK (test_points >= 0), receipt jsonb NOT NULL,
    create_time timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_sim_purchase_org ON org_point_simulated_purchase (tenant_id, org_id);

-- Preserve receipts and request keys if a previous candidate issued real-pool credits.
-- Never make a negative formal balance or silently write off already consumed credits.
INSERT INTO org_point_simulated_purchase(id,tenant_id,org_id,biz_key,price_fen,test_points,receipt,create_time)
SELECT id,split_part(biz_key,':',2)::bigint,org_id,biz_key,(price_snapshot->>'priceFen')::bigint,
       available_delta,price_snapshot,create_time
FROM org_point_ledger WHERE type='SIMULATED_PACKAGE';

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM org_point_simulated_purchase s LEFT JOIN org_point_account a ON a.org_id=s.org_id
               GROUP BY s.org_id,a.balance HAVING a.balance IS NULL OR a.balance < sum(s.test_points)) THEN
        RAISE EXCEPTION 'Historical simulated points already spent: reconcile affected org_point_ledger entries before migrating';
    END IF;
END $$;

UPDATE org_point_account a SET balance=a.balance-s.points,version=a.version+1,update_time=now()
FROM (SELECT org_id,sum(test_points) AS points FROM org_point_simulated_purchase GROUP BY org_id) s
WHERE a.org_id=s.org_id;
INSERT INTO org_point_ledger(id,org_id,biz_key,operation_time,type,available_delta,ref_type,ref_id,note)
SELECT -id,org_id,'isolate:'||biz_key,now(),'SIMULATED_PACKAGE_TRANSFER',-test_points,'SIMULATED_PACKAGE',id,'模拟积分转入测试账簿'
FROM org_point_simulated_purchase;
