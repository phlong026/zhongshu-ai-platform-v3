package cn.iocoder.yudao.module.franchise.engineering;

import cn.iocoder.yudao.module.franchise.api.CustomerWorkflowFactsPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A real primary project, actual date and completed START record are required; status alone is never evidence. */
@Component
public class EngWorkflowFactsAdapter implements CustomerWorkflowFactsPort {
    private final NamedParameterJdbcTemplate jdbc;
    public EngWorkflowFactsAdapter(JdbcTemplate jdbc) { this.jdbc = new NamedParameterJdbcTemplate(jdbc); }

    @Override
    public List<Fact> findFacts(long tenantId, Map<Long, Long> customerCompanies) {
        if (customerCompanies.isEmpty()) return List.of();
        var rows = jdbc.query("""
                SELECT p.id,p.company_id,p.customer_id,p.actual_start_at,p.manager_user_id,p.status,p.revision,
                  EXISTS(SELECT 1 FROM fr_eng_plan_node n JOIN fr_eng_site_record r
                    ON r.company_id=n.company_id AND r.project_id=n.project_id AND r.node_id=n.id
                    WHERE n.company_id=p.company_id AND n.project_id=p.id AND n.milestone='START'
                      AND n.status='DONE' AND n.completed_at=p.actual_start_at
                      AND r.completion=TRUE AND r.result='PASS' AND r.occurred_at=p.actual_start_at
                      AND CASE WHEN jsonb_typeof(r.file_asset_ids)='array' THEN jsonb_array_length(r.file_asset_ids)>0 ELSE FALSE END) AS start_evidence
                FROM fr_eng_project p JOIN system_dept d ON d.id=p.company_id
                WHERE d.tenant_id=:tenant AND d.status=0 AND d.deleted=0
                  AND p.source_type<>'INCREMENT' AND p.customer_id IN (:customers)
                ORDER BY p.create_time DESC,p.id DESC
                """, Map.of("tenant", tenantId, "customers", customerCompanies.keySet()), (rs, n) -> {
            long customer = rs.getLong("customer_id"), company = rs.getLong("company_id");
            if (!Objects.equals(customerCompanies.get(customer), company)) return null;
            LocalDate start = rs.getObject("actual_start_at", LocalDate.class);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("projectId", Long.toString(rs.getLong("id")));
            fields.put("actualStartAt", start == null ? null : start.toString());
            fields.put("managerUserId", Long.toString(rs.getLong("manager_user_id")));
            fields.put("status", rs.getString("status"));
            fields.put("sourceRevision", rs.getLong("revision"));
            List<String> missing = new ArrayList<>();
            if (start == null || start.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))) missing.add("等待工程模块登记有效的实际开工日期");
            if (!rs.getBoolean("start_evidence")) missing.add("待补录工程正式开工记录及凭据");
            return new Fact(customer, company, "START", start, fields, missing.isEmpty(), List.copyOf(missing));
        });
        // Increment projects never complete a customer's first construction step; the newest primary project is authoritative.
        Map<Long, Fact> latest = new LinkedHashMap<>();
        rows.stream().filter(Objects::nonNull).forEach(fact -> latest.putIfAbsent(fact.customerId(), fact));
        return List.copyOf(latest.values());
    }
}
