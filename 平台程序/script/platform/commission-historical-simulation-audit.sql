-- Use the IDs from franchise.commission.simulation-org-ids, for example:
-- psql -v test_org_ids='{}' -f script/platform/commission-historical-simulation-audit.sql
-- An empty array is correct only when simulation is disabled.
\set ON_ERROR_STOP on
\if :{?test_org_ids}
\else
  \echo Supply the configured test organization IDs as test_org_ids (PostgreSQL bigint array).
  \quit
\endif

\if :{?include_test_pairs}
\else
  \set include_test_pairs false
\endif

BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '30s';

WITH config AS (SELECT :'test_org_ids'::bigint[] AS test_orgs)
SELECT s.company_id,
       s.design_org_id,
       s.commission_id,
       s.submission_version,
       c.submission_version AS current_submission_version,
       s.submission_version=c.submission_version AS current_version,
       (s.company_id=ANY(cfg.test_orgs) AND s.design_org_id=ANY(cfg.test_orgs)) AS test_org_pair,
       s.status AS simulation_status,
       c.status AS commission_status,
       s.amount_cents AS simulation_amount_cents,
       CASE
           WHEN dispatch.dispatch_seq IS NULL THEN '待下发'
           WHEN c.dispatch_seq=dispatch.dispatch_seq AND c.dispatched_submission_version=s.submission_version THEN '已下发'
           ELSE '曾下发，需核对当前版本'
       END AS delivery_state,
       CASE
           WHEN packet.status='ACCEPTED' THEN '已移交'
           WHEN packet.status='PENDING' THEN '待工程接收'
           WHEN packet.status='RETURNED' THEN '移交已退回'
           WHEN dispatch.dispatch_seq IS NOT NULL AND (packet.status IS NULL) THEN '待移交'
       END AS handover_state,
       dispatch.dispatch_seq AS latest_dispatch_seq,
       p.id AS formal_payable_id,
       money.expected_cents,
       paid.net_cents AS store_paid_net_cents,
       received.net_cents AS hq_received_net_cents,
       received.origins_valid AS hq_payment_origins_valid,
       EXISTS (
           SELECT 1 FROM pf_command_event e
           WHERE e.domain='franchise' AND e.aggregate_type='commission-historical-settlement'
             AND e.command='commission-historical-settlement-prepare' AND e.org_id=s.company_id
             AND e.aggregate_id=s.commission_id AND e.revision=s.submission_version
       ) AS reviewed_for_settlement
FROM fr_commission_simulated_order s
JOIN fr_commission c ON c.id=s.commission_id AND c.company_id=s.company_id
CROSS JOIN config cfg
LEFT JOIN LATERAL (
    SELECT m.dispatch_seq
    FROM fr_commission_dispatch_manifest m
    WHERE m.commission_id=s.commission_id AND m.submission_version=s.submission_version
    ORDER BY m.dispatch_seq DESC LIMIT 1
) dispatch ON true
LEFT JOIN LATERAL (
    SELECT h.status FROM fr_commission_handover h
    WHERE h.commission_id=s.commission_id AND h.company_id=s.company_id AND h.dispatch_seq=dispatch.dispatch_seq
    ORDER BY h.version DESC LIMIT 1
) packet ON true
CROSS JOIN LATERAL (
    SELECT s.commission_id||':'||s.submission_version||':FULL' AS ref, s.amount_cents AS expected_cents
) money
LEFT JOIN fr_fin_payable p ON p.company_id=s.company_id AND p.design_org_id=s.design_org_id
   AND p.source_type='design' AND p.source_ref=money.ref
LEFT JOIN LATERAL (
    SELECT COALESCE(sum(m.amount_cents-COALESCE(m.pending_link_cents,0)-COALESCE((
        SELECT sum(f.amount_cents) FROM fr_fin_refund f WHERE f.payment_id=m.id AND f.status='已核实'
    ),0)),0) AS net_cents
    FROM fr_fin_payment m WHERE m.payable_id=p.id AND m.status='已核实'
) paid ON true
LEFT JOIN LATERAL (
    SELECT COALESCE(sum(o.net),0) AS net_cents,
           NOT COALESCE(bool_or(o.net>0 AND (
               p.id IS NULL OR m.status IS DISTINCT FROM '已核实' OR m.company_id IS DISTINCT FROM s.company_id
               OR m.payable_id IS DISTINCT FROM p.id OR o.net>m.amount_cents-COALESCE(m.pending_link_cents,0)-COALESCE((
                   SELECT sum(f.amount_cents) FROM fr_fin_refund f WHERE f.payment_id=m.id AND f.status='已核实'
               ),0)
           )),false) AS origins_valid
    FROM (
        SELECT r.origin_payment_id,sum(r.amount_cents-COALESCE((
            SELECT sum(f.amount_cents) FROM fr_fin_refund f WHERE f.receipt_id=r.id AND f.status='已核实'
        ),0)) AS net
        FROM fr_fin_receipt r WHERE r.company_id=s.design_org_id AND r.source_type='design'
            AND r.source_ref=money.ref AND r.status='已登记'
        GROUP BY r.origin_payment_id
    ) o LEFT JOIN fr_fin_payment m ON m.id=o.origin_payment_id
) received ON true
WHERE :'include_test_pairs'::boolean OR NOT (s.company_id=ANY(cfg.test_orgs) AND s.design_org_id=ANY(cfg.test_orgs))
ORDER BY s.company_id,s.commission_id,s.submission_version;

COMMIT;
