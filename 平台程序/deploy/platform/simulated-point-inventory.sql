-- Run before the upgrade, using a read-only session. Each row belongs to an
-- organization with at least one historical simulated award.
BEGIN READ ONLY;
SELECT l.org_id, a.balance AS current_formal_balance, count(*) OVER (PARTITION BY l.org_id) AS ledger_count,
       l.id, l.type, l.operation_id, l.operation_time, l.available_delta, l.biz_key, l.ref_type, l.ref_id,
       l.actor_user_id, l.actor_post_id, l.price_snapshot,
       (l.type='RESERVE' AND NOT EXISTS (
           SELECT 1 FROM org_point_ledger t WHERE t.operation_id=l.operation_id AND t.type IN ('SETTLE','RELEASE')
       )) AS outstanding_reservation
FROM org_point_ledger l JOIN org_point_account a ON a.org_id=l.org_id
WHERE EXISTS (SELECT 1 FROM org_point_ledger s WHERE s.org_id=l.org_id AND s.type='SIMULATED_PACKAGE')
ORDER BY l.org_id,l.create_time,l.id;
COMMIT;
