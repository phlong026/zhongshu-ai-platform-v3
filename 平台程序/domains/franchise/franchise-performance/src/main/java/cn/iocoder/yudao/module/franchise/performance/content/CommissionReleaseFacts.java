package cn.iocoder.yudao.module.franchise.performance.content;

/** 批量读取一家门店各期的实收、退款、付款和历史核对凭证，供待办与导出共用。 */
final class CommissionReleaseFacts {
    private CommissionReleaseFacts() {}

    static String selectSql() {
        String stage = "CASE r.stage WHEN 'START' THEN 'SIGN_AFTER' "
                + "WHEN 'TOPPING' THEN 'CAP_DONE' ELSE r.stage END";
        String required = "COALESCE(CASE WHEN contract.id IS NOT NULL THEN sale_stage.amount_cents ELSE term.amount_cents END, 0)";
        return "SELECT p.id AS policy_id, p.assignee_user_id, p.assignee_name, "
                + "p.update_time AS policy_update_time, p.total_cents, p.start_cents, p.base_done_cents, "
                + "p.topping_cents, p.version AS current_version, r.project_id, project.name AS project_name, r.stage, r.policy_version, "
                + "r.receipt_signature, r.reconciliations -> -1 ->> 'paidCents' AS reviewed_paid, fp.total_cents AS payable_cents, "
                + "CASE WHEN unified.refunded > 0 AND r.receipt_signature NOT LIKE '%|refunded:%' "
                + "AND jsonb_typeof(r.reconciliations->-1)='object' THEN (" + LegacyCommissionReview.EVIDENCE
                + ") END AS legacy_review, "
                + "COALESCE((SELECT SUM(pm.amount_cents) FROM fr_fin_payment pm "
                + "WHERE pm.company_id = r.company_id AND pm.payable_id = r.payable_id AND pm.status = '已核实'), 0) AS paid_cents, "
                + required + " AS fact_required, "
                + "CASE WHEN contract.id IS NOT NULL THEN COALESCE(unified.received, 0) "
                + "ELSE COALESCE(engineering.received, 0) END AS fact_received, "
                + "'required:' || " + required + " || "
                + "CASE WHEN contract.id IS NOT NULL THEN '|unified:' || contract.id || ':' || " + stage
                + " || ':' || COALESCE(unified.received, 0) "
                + " || CASE WHEN unified.refunded > 0 THEN '|refunded:' || unified.refunded ELSE '' END "
                + "ELSE COALESCE('|' || engineering.signature_parts, '') END AS fact_signature "
                + "FROM fr_perf_commission_release r "
                + "LEFT JOIN fr_perf_commission_policy p "
                + "ON p.id = r.policy_id AND p.company_id = r.company_id "
                + "LEFT JOIN fr_fin_payable fp ON fp.id = r.payable_id AND fp.company_id = r.company_id "
                + "LEFT JOIN fr_eng_project project ON project.id = r.project_id "
                + "AND project.company_id = r.company_id "
                + "LEFT JOIN fr_eng_collection_term term ON term.project_id = r.project_id "
                + "AND term.company_id = r.company_id AND term.stage = " + stage + " "
                + "LEFT JOIN LATERAL (SELECT " + cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.PROJECT_CONTRACT
                + " AS id FROM fr_eng_project p WHERE p.id=project.id AND p.company_id=r.company_id) contract ON TRUE "
                + "LEFT JOIN fr_sale_payment_stage sale_stage ON sale_stage.company_id = r.company_id "
                + "AND sale_stage.contract_id = contract.id AND sale_stage.stage = " + stage + " "
                + "LEFT JOIN LATERAL (SELECT COALESCE(SUM(sr.amount_cents), 0) "
                + "- COALESCE(SUM(refund.refunded), 0) AS received, COALESCE(SUM(refund.refunded), 0) AS refunded "
                + "FROM fr_sale_stage_receipt sr LEFT JOIN LATERAL ("
                + "SELECT COALESCE(SUM(g.amount_cents), 0) AS refunded FROM fr_fin_refund g "
                + "WHERE g.company_id = r.company_id AND g.receipt_id = sr.receipt_id "
                + "AND g.status = '已核实' AND g.replaced_by_id IS NULL) refund ON TRUE "
                + "WHERE sr.company_id = r.company_id AND sr.stage_id = sale_stage.id) unified ON contract.id IS NOT NULL "
                + "LEFT JOIN LATERAL (SELECT COALESCE(SUM(GREATEST(0, c.amount_cents "
                + "- COALESCE(refund.refunded, 0))), 0) AS received, "
                + "string_agg('c:' || c.id || ':' || c.amount_cents || ':' || COALESCE(refund.refunded, 0), "
                + "'|' ORDER BY c.id) AS signature_parts FROM fr_eng_collection c "
                + "LEFT JOIN LATERAL (SELECT COALESCE(SUM(g.amount_cents), 0) AS refunded "
                + "FROM fr_fin_refund g WHERE g.company_id = r.company_id AND g.receipt_id = c.receipt_id "
                + "AND g.status = '已核实' AND g.replaced_by_id IS NULL) refund ON c.receipt_id IS NOT NULL "
                + "WHERE c.company_id = r.company_id AND c.project_id = r.project_id "
                + "AND c.stage = " + stage + ") engineering ON contract.id IS NULL ";
    }
}
