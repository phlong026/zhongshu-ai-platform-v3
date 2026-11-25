package cn.iocoder.yudao.module.franchise.sales;

import cn.iocoder.yudao.module.franchise.api.CustomerWorkflowFactsPort;
import cn.iocoder.yudao.module.franchise.api.AssetEvidenceQueryPort;
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

/** The sales domain owns the signing fact. CRM may only consume this scoped bulk projection. */
@Component
public class SaleWorkflowFactsAdapter implements CustomerWorkflowFactsPort {
    private final NamedParameterJdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<AssetEvidenceQueryPort> evidence;
    public SaleWorkflowFactsAdapter(JdbcTemplate jdbc) { this.jdbc = new NamedParameterJdbcTemplate(jdbc); }

    @Override
    public List<Fact> findFacts(long tenantId, Map<Long, Long> customerCompanies) {
        if (customerCompanies.isEmpty()) return List.of();
        List<Fact> facts = jdbc.query("""
                SELECT c.id,c.company_id,c.customer_id,c.signed_date,c.total_cents,c.asset_id,c.status,c.revision
                FROM fr_sale_contract c JOIN system_dept d ON d.id=c.company_id
                WHERE d.tenant_id=:tenant AND d.status=0 AND d.deleted=0 AND c.deleted=FALSE
                  AND c.status IN ('WAIT_SIGN','SIGNED') AND c.customer_id IN (:customers)
                """, Map.of("tenant", tenantId, "customers", customerCompanies.keySet()), (rs, n) -> {
            long customer = rs.getLong("customer_id"), company = rs.getLong("company_id");
            if (!Objects.equals(customerCompanies.get(customer), company)) return null;
            LocalDate signed = rs.getObject("signed_date", LocalDate.class);
            Long asset = rs.getObject("asset_id", Long.class);
            long total = rs.getLong("total_cents");
            String status = rs.getString("status");
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("contractId", Long.toString(rs.getLong("id")));
            fields.put("signedDate", signed == null ? null : signed.toString());
            fields.put("totalCents", Long.toString(total));
            fields.put("assetId", asset == null ? null : asset.toString());
            fields.put("status", status);
            fields.put("sourceRevision", rs.getLong("revision"));
            List<String> missing = new ArrayList<>();
            if (!"SIGNED".equals(status)) missing.add("请在合同模块办理正式签约");
            if (signed == null || signed.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))) missing.add("待补录有效签约日期");
            if (asset == null || asset <= 0) missing.add("待补录已签合同附件");
            if (total <= 0) missing.add("待补录合同金额");
            return new Fact(customer, company, "CONTRACT", signed, fields, missing.isEmpty(), List.copyOf(missing));
        }).stream().filter(Objects::nonNull).toList();
        java.util.Set<Long> requested = new java.util.HashSet<>();
        facts.forEach(fact -> {
            Object asset = fact.fields().get("assetId");
            if (asset != null && Long.parseLong(String.valueOf(asset)) > 0) requested.add(Long.parseLong(String.valueOf(asset)));
        });
        var verifier = evidence == null ? null : evidence.getIfAvailable();
        var accepted = verifier == null ? Map.<Long, Long>of() : verifier.acceptedOwners(tenantId, requested, java.util.Set.of("CONTRACT_ATTACHMENT"));
        return facts.stream().map(fact -> {
            Object asset = fact.fields().get("assetId");
            if (asset == null || Long.parseLong(String.valueOf(asset)) <= 0) return fact;
            long assetId = Long.parseLong(String.valueOf(asset));
            if (Objects.equals(accepted.get(assetId), fact.companyId())) return fact;
            List<String> missing = new ArrayList<>(fact.missingEvidence()); missing.add("合同附件未通过验收或已失效，请在合同模块补录");
            return new Fact(fact.customerId(), fact.companyId(), fact.stepCode(), fact.occurredOn(), fact.fields(), false, List.copyOf(missing));
        }).toList();
    }
}
