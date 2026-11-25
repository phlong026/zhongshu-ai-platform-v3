-- Read-only inventory. Supply exact tenant and JSON company IDs through psql variables.
-- Example: psql -X -v ON_ERROR_STOP=1 -v tenant=1 -v company_ids='[123,456]' -f inventory.sql
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
WITH requested AS (
 SELECT value::bigint AS org_id FROM jsonb_array_elements_text(:'company_ids'::jsonb)
), scope AS (
 SELECT o.dept_id AS org_id,o.org_type,o.status,d.status AS department_status,d.deleted
 FROM pf_org_unit o JOIN system_dept d ON d.id=o.dept_id JOIN requested r ON r.org_id=o.dept_id
 WHERE d.tenant_id=:'tenant'::bigint AND o.kind='ORG'
), output AS (
 SELECT jsonb_build_object('kind','scope','requested', (SELECT count(*) FROM requested),
   'matched',(SELECT count(*) FROM scope),'organizations',COALESCE((SELECT jsonb_agg(to_jsonb(s)) FROM scope s),'[]')) AS item
 UNION ALL
 SELECT jsonb_build_object('kind','legacyPosts','rows',COALESCE(jsonb_agg(jsonb_build_object(
   'postId',p.id::text,'companyId',p.org_id::text,'role',p.role_code,'status',p.status)),'[]'))
 FROM pf_staff_post p JOIN scope s ON s.org_id=p.org_id
 WHERE p.status='ACTIVE' AND p.role_code IN ('hq-design-admin','hq-designer','hq-design-lead')
 UNION ALL
 SELECT jsonb_build_object('kind','legacyCommissions','rows',COALESCE(jsonb_agg(jsonb_build_object(
   'commissionId',c.id::text,'companyId',c.company_id::text,'customerId',c.customer_id::text,'status',c.status,
   'action',CASE WHEN c.status='ACCEPTED' THEN 'KEEP_ENGINEERING_HISTORY'
     WHEN c.status='HANDOVER_SUBMITTED' THEN 'RESOLVE_EXISTING_HANDOVER'
     ELSE 'EXPLICIT_R09_CONVERSION' END)),'[]'))
 FROM fr_commission c WHERE (c.company_id IN(SELECT org_id FROM scope) OR c.design_org_id IN(SELECT org_id FROM scope))
 AND COALESCE(to_jsonb(c)->>'workflow_mode','LEGACY')='LEGACY'
 UNION ALL
 SELECT jsonb_build_object('kind','legacyPendingApprovals','rows',COALESCE(jsonb_agg(jsonb_build_object(
   'approvalId',a.id::text,'companyId',a.org_id::text,'subject',a.subject_type,'subjectId',a.subject_id::text,
   'role',a.approver_role_code)),'[]'))
 FROM pf_approval a WHERE a.org_id IN(SELECT org_id FROM scope) AND a.status='PENDING'
   AND (a.approver_role_code LIKE 'hq-design-%' OR a.subject_type='commission-fee')
 UNION ALL
 SELECT jsonb_build_object('kind','missingContractEvidence','rows',COALESCE(jsonb_agg(jsonb_build_object(
   'contractId',c.id::text,'customerId',c.customer_id::text,'companyId',c.company_id::text,
   'missingDate',c.signed_date IS NULL,'missingAsset',c.asset_id IS NULL)),'[]'))
 FROM fr_sale_contract c WHERE c.company_id IN(SELECT org_id FROM scope) AND NOT c.deleted AND c.status='SIGNED'
   AND (c.signed_date IS NULL OR c.asset_id IS NULL)
 UNION ALL
 SELECT jsonb_build_object('kind','startedProjectEvidence','rows',COALESCE(jsonb_agg(jsonb_build_object(
   'projectId',p.id::text,'customerId',p.customer_id::text,'companyId',p.company_id::text,
   'actualStart',p.actual_start_at,'siteRecords',(SELECT count(*) FROM fr_eng_site_record r WHERE r.project_id=p.id AND r.company_id=p.company_id))),'[]'))
 FROM fr_eng_project p WHERE p.company_id IN(SELECT org_id FROM scope) AND p.actual_start_at IS NOT NULL
 UNION ALL
 SELECT jsonb_build_object('kind','companyPools','rows',COALESCE(jsonb_agg(jsonb_build_object(
   'companyId',s.org_id::text,'poolExists',a.org_id IS NOT NULL,'balance',a.balance::text,'status',a.status,
   'ledgerRows',(SELECT count(*) FROM org_point_ledger l WHERE l.org_id=s.org_id))),'[]'))
 FROM scope s LEFT JOIN org_point_account a ON a.org_id=s.org_id
 UNION ALL
 SELECT jsonb_build_object('kind','financialHistory','rows',COALESCE(jsonb_agg(jsonb_build_object(
   'companyId',s.org_id::text,'receipts',(SELECT count(*) FROM fr_fin_receipt r WHERE r.company_id=s.org_id),
   'payments',(SELECT count(*) FROM fr_fin_payment p WHERE p.company_id=s.org_id),
   'refunds',(SELECT count(*) FROM fr_fin_refund r WHERE r.company_id=s.org_id))),'[]')) FROM scope s
)
SELECT jsonb_pretty(jsonb_agg(item)) FROM output;
ROLLBACK;
