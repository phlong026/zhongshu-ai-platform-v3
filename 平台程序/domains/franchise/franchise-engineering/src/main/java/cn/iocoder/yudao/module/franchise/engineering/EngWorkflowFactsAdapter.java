package cn.iocoder.yudao.module.franchise.engineering;

import cn.iocoder.yudao.module.franchise.api.CustomerWorkflowFactsPort;
import cn.iocoder.yudao.module.franchise.api.AssetEvidenceQueryPort;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
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
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<AssetEvidenceQueryPort> evidence;
    private record Candidate(Fact fact, List<List<Long>> records) { }
    public EngWorkflowFactsAdapter(JdbcTemplate jdbc) { this.jdbc = new NamedParameterJdbcTemplate(jdbc); }

    @Override
    public List<Fact> findFacts(long tenantId, Map<Long, Long> customerCompanies) {
        if (customerCompanies.isEmpty()) return List.of();
        var rows = jdbc.query("""
                SELECT p.id,p.company_id,p.customer_id,p.actual_start_at,p.manager_user_id,p.status,p.revision,
                  COALESCE((SELECT jsonb_agg(r.file_asset_ids ORDER BY r.id) FROM fr_eng_plan_node n JOIN fr_eng_site_record r
                    ON r.company_id=n.company_id AND r.project_id=n.project_id AND r.node_id=n.id
                    WHERE n.company_id=p.company_id AND n.project_id=p.id AND n.milestone='START'
                      AND n.status='DONE' AND n.completed_at=p.actual_start_at
                      AND r.completion=TRUE AND r.result='PASS' AND r.occurred_at=p.actual_start_at
                      AND jsonb_typeof(r.file_asset_ids)='array'), '[]'::jsonb)::text AS start_files
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
            return new Candidate(new Fact(customer, company, "START", start, fields, missing.isEmpty(), List.copyOf(missing)),
                    records(rs.getString("start_files")));
        });
        // Increment projects never complete a customer's first construction step; newest primary is authoritative.
        Map<Long, Candidate> latest = new LinkedHashMap<>();
        rows.stream().filter(Objects::nonNull).forEach(row -> latest.putIfAbsent(row.fact().customerId(), row));
        java.util.Set<Long> ids=new java.util.HashSet<>();
        latest.values().forEach(row -> row.records().forEach(ids::addAll));
        var verifier=evidence==null?null:evidence.getIfAvailable();
        var owners=verifier==null?Map.<Long,Long>of():verifier.acceptedOwners(tenantId,ids,java.util.Set.of("ENG_SITE_PHOTO","ENG_DRAWING"));
        return latest.values().stream().map(row -> {
            Fact fact=row.fact();
            boolean valid=row.records().stream().anyMatch(files -> !files.isEmpty()
                    && files.stream().allMatch(id -> Objects.equals(owners.get(id),fact.companyId())));
            if (valid) return fact;
            List<String> missing=new ArrayList<>(fact.missingEvidence());
            missing.add("待补录工程正式开工记录及通过验收的本公司凭据");
            return new Fact(fact.customerId(),fact.companyId(),fact.stepCode(),fact.occurredOn(),fact.fields(),false,List.copyOf(missing));
        }).toList();
    }

    /** A malformed element invalidates its whole record; strings or numeric longs are both legacy-compatible. */
    private static List<List<Long>> records(String value) {
        var result=new ArrayList<List<Long>>();
        try {
            var rows=JsonUtils.parseTree(value);
            if (!rows.isArray()) return List.of();
            for (var row:rows) {
                if (!row.isArray() || row.isEmpty()) continue;
                var files=new ArrayList<Long>(); boolean valid=true;
                for (var file:row) {
                    String id=file.asText();
                    if (!(file.isTextual() || file.isIntegralNumber()) || !id.matches("[1-9][0-9]{0,18}")) { valid=false; break; }
                    try { files.add(Long.parseLong(id)); } catch (NumberFormatException invalid) { valid=false; break; }
                }
                if (valid) result.add(List.copyOf(files));
            }
        } catch (RuntimeException invalid) { return List.of(); }
        return List.copyOf(result);
    }
}
