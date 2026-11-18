-- Use the IDs from franchise.commission.simulation-org-ids, for example:
-- psql -v test_org_ids='{}' -f script/platform/commission-historical-simulation-audit.sql
-- An empty array is correct only when simulation is disabled.
\set ON_ERROR_STOP on
\if :{?test_org_ids}
\else
  \echo Supply the configured test organization IDs as test_org_ids (PostgreSQL bigint array).
  \quit
\endif

BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;

WITH config AS (SELECT :'test_org_ids'::bigint[] AS test_orgs)
SELECT s.company_id,
       s.design_org_id,
       s.commission_id,
       s.submission_version,
       s.status AS simulation_status,
       c.status AS commission_status,
       s.amount_cents AS simulation_amount_cents,
       CASE
           WHEN dispatch.dispatch_seq IS NULL THEN '待下发'
           WHEN c.dispatch_seq=dispatch.dispatch_seq AND c.dispatched_submission_version=s.submission_version THEN '已下发'
           ELSE '曾下发，需核对当前版本'
       END AS delivery_state,
       CASE c.status
           WHEN 'DISPATCHED' THEN '待移交'
           WHEN 'HANDOVER_RETURNED' THEN '移交已退回'
           WHEN 'HANDOVER_SUBMITTED' THEN '待工程接收'
           WHEN 'ACCEPTED' THEN '已移交'
       END AS handover_state,
       dispatch.dispatch_seq AS latest_dispatch_seq,
       p.id AS formal_payable_id,
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
LEFT JOIN fr_fin_payable p ON p.company_id=s.company_id AND p.design_org_id=s.design_org_id
   AND p.source_type='design' AND p.source_ref=s.commission_id||':'||s.submission_version||':FULL'
WHERE NOT (s.company_id=ANY(cfg.test_orgs) AND s.design_org_id=ANY(cfg.test_orgs))
ORDER BY s.company_id,s.commission_id,s.submission_version;

COMMIT;
