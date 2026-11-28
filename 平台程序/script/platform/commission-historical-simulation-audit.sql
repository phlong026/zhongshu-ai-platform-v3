-- Historical simulated design-fee settlement audit (read-only).
-- Run with:
--   psql -v test_org_ids='{}' -v include_test_pairs=false \
--     -f script/platform/commission-historical-simulation-audit.sql
-- test_org_ids is a PostgreSQL bigint array of known test organizations.
-- include_test_pairs=true includes pairs where BOTH store and HQ are test organizations.
-- This query does not modify simulated orders, payables, payments, receipts or command events.
\set ON_ERROR_STOP on
\if :{?test_org_ids}
\else
  \echo Supply test_org_ids as a PostgreSQL bigint array (use '{}' when none are known).
  \quit
\endif
\if :{?include_test_pairs}
\else
  \set include_test_pairs false
\endif

BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '30s';

WITH config AS (
    SELECT :'test_org_ids'::bigint[] AS test_orgs
),
orders AS (
    SELECT s.commission_id, s.submission_version, s.company_id, s.design_org_id,
           s.order_number, s.amount_cents AS simulation_amount_cents, s.status AS simulation_status,
           s.channel AS simulation_channel, s.paid_at AS simulation_paid_at,
           c.submission_version AS current_submission_version, c.status AS commission_status,
           sub.mode AS submission_mode, sub.total_cents AS submission_total_cents,
           sub.first_cents AS submission_first_cents,
           (s.submission_version=c.submission_version) AS current_version,
           (s.company_id=ANY(cfg.test_orgs) AND s.design_org_id=ANY(cfg.test_orgs)) AS test_org_pair,
           dispatch.dispatch_seq,
           CASE
             WHEN dispatch.dispatch_seq IS NULL THEN '待下发'
             WHEN c.dispatch_seq=dispatch.dispatch_seq AND c.dispatched_submission_version=s.submission_version THEN '已下发'
             ELSE '曾下发，需核对当前版本'
           END AS delivery_state,
           CASE
             WHEN packet.status='ACCEPTED' THEN '已移交'
             WHEN packet.status='PENDING' THEN '待工程接收'
             WHEN packet.status='RETURNED' THEN '移交已退回'
             WHEN dispatch.dispatch_seq IS NOT NULL AND packet.status IS NULL THEN '待移交'
             ELSE '未进入移交'
           END AS handover_state,
           EXISTS (
             SELECT 1 FROM pf_command_event e
             WHERE e.domain='franchise' AND e.aggregate_type='commission-historical-settlement'
               AND e.command='commission-historical-settlement-prepare' AND e.org_id=s.company_id
               AND e.aggregate_id=s.commission_id AND e.revision=s.submission_version
           ) AS reviewed_for_settlement
    FROM fr_commission_simulated_order s
    JOIN fr_commission c ON c.id=s.commission_id AND c.company_id=s.company_id
    JOIN fr_commission_submission sub
      ON sub.commission_id=s.commission_id AND sub.version=s.submission_version
    CROSS JOIN config cfg
    LEFT JOIN LATERAL (
        SELECT m.dispatch_seq
        FROM fr_commission_dispatch_manifest m
        WHERE m.commission_id=s.commission_id AND m.submission_version=s.submission_version
        ORDER BY m.dispatch_seq DESC LIMIT 1
    ) dispatch ON true
    LEFT JOIN LATERAL (
        SELECT h.status
        FROM fr_commission_handover h
        WHERE h.commission_id=s.commission_id AND h.company_id=s.company_id
          AND h.dispatch_seq=dispatch.dispatch_seq
        ORDER BY h.version DESC LIMIT 1
    ) packet ON true
    WHERE :'include_test_pairs'::boolean
       OR NOT (s.company_id=ANY(cfg.test_orgs) AND s.design_org_id=ANY(cfg.test_orgs))
),
stages AS (
    SELECT o.*, 'FULL'::text AS settlement_stage, o.submission_total_cents AS expected_cents
    FROM orders o WHERE o.submission_mode='AREA_FULL'
    UNION ALL
    SELECT o.*, 'FIRST'::text AS settlement_stage, o.submission_first_cents AS expected_cents
    FROM orders o WHERE o.submission_mode='LEGACY_SPLIT'
    UNION ALL
    SELECT o.*, 'TAIL'::text AS settlement_stage,
           o.submission_total_cents-o.submission_first_cents AS expected_cents
    FROM orders o WHERE o.submission_mode='LEGACY_SPLIT'
),
scoped AS (
    SELECT s.*, s.commission_id||':'||s.submission_version||':'||s.settlement_stage AS source_ref,
           s.reviewed_for_settlement AS payable_expected
    FROM stages s
),
facts AS (
    SELECT s.*,
           p.id AS formal_payable_id,
           COALESCE(paid.payment_count,0) AS store_payment_count,
           COALESCE(paid.net_cents,0) AS store_paid_net_cents,
           COALESCE(received.receipt_count,0) AS hq_receipt_count,
           COALESCE(received.net_cents,0) AS hq_received_net_cents,
           COALESCE(received.origins_valid,true) AS hq_payment_origins_valid
    FROM scoped s
    LEFT JOIN fr_fin_payable p
      ON p.company_id=s.company_id AND p.design_org_id=s.design_org_id
     AND p.source_type='design' AND p.source_ref=s.source_ref
    LEFT JOIN LATERAL (
        SELECT count(*) AS payment_count,
               COALESCE(sum(CASE WHEN m.status='已核实' THEN
                   m.amount_cents-COALESCE(m.pending_link_cents,0)-COALESCE((
                       SELECT sum(f.amount_cents) FROM fr_fin_refund f
                       WHERE f.payment_id=m.id AND f.status='已核实'
                   ),0) ELSE 0 END),0) AS net_cents
        FROM fr_fin_payment m
        WHERE m.payable_id=p.id
    ) paid ON true
    LEFT JOIN LATERAL (
        SELECT COALESCE(sum(o.source_receipt_count),0) AS receipt_count, COALESCE(sum(o.net),0) AS net_cents,
               NOT COALESCE(bool_or(o.net>0 AND (
                   p.id IS NULL OR m.status IS DISTINCT FROM '已核实'
                   OR m.company_id IS DISTINCT FROM s.company_id
                   OR m.payable_id IS DISTINCT FROM p.id
                   OR o.net>m.amount_cents-COALESCE(m.pending_link_cents,0)-COALESCE((
                       SELECT sum(f.amount_cents) FROM fr_fin_refund f
                       WHERE f.payment_id=m.id AND f.status='已核实'
                   ),0)
               )),false) AS origins_valid
        FROM (
            SELECT r.origin_payment_id,
                   count(*) AS source_receipt_count,
                   sum(CASE WHEN r.status='已登记' THEN
                       r.amount_cents-COALESCE((
                           SELECT sum(f.amount_cents) FROM fr_fin_refund f
                           WHERE f.receipt_id=r.id AND f.status='已核实'
                       ),0) ELSE 0 END) AS net
            FROM fr_fin_receipt r
            WHERE r.company_id=s.design_org_id AND r.source_type='design'
              AND r.source_ref=s.source_ref
            GROUP BY r.origin_payment_id
        ) o
        LEFT JOIN fr_fin_payment m ON m.id=o.origin_payment_id
    ) received ON true
)
SELECT company_id, design_org_id, commission_id, submission_version, current_submission_version,
       current_version, test_org_pair, commission_status, submission_mode, settlement_stage,
       simulation_status, order_number, simulation_amount_cents, submission_total_cents,
       submission_first_cents, expected_cents,
       (simulation_amount_cents=submission_total_cents) AS simulation_matches_submission_total,
       delivery_state, handover_state, dispatch_seq,
       reviewed_for_settlement, payable_expected, formal_payable_id,
       store_payment_count, store_paid_net_cents, hq_receipt_count, hq_received_net_cents,
       hq_payment_origins_valid,
       (store_payment_count=0 AND hq_receipt_count=0) AS no_actual_finance_records,
       CASE
         WHEN expected_cents<=0 THEN '无有效应收金额，需核对提交版本'
         WHEN NOT current_version THEN '历史旧版本，禁止自动迁移'
         WHEN NOT payable_expected THEN '尚未到生成该期正式应付的节点'
         WHEN formal_payable_id IS NULL THEN '应付缺失，需人工核查'
         WHEN NOT hq_payment_origins_valid THEN '总部核收来源异常'
         WHEN store_paid_net_cents>=expected_cents AND hq_received_net_cents>=expected_cents THEN '该期实际收付净额达标'
         WHEN store_payment_count=0 AND hq_receipt_count=0 THEN '纯模拟候选：未发现正式财务流水'
         ELSE '存在真实财务流水但净额不足，需核对补款/退款'
       END AS reconciliation_state
FROM facts
ORDER BY company_id, commission_id, submission_version,
         CASE settlement_stage WHEN 'FULL' THEN 1 WHEN 'FIRST' THEN 2 ELSE 3 END;

COMMIT;
