package cn.iocoder.yudao.module.franchise.performance.content;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PerfCycleEvidence {
    private PerfCycleEvidence() {}

    static List<Map<String, Object>> freeze(NamedParameterJdbcTemplate jdbc, long company,
                                             List<PerfMetrics.SourceRow> facts) {
        List<Map<String, Object>> rows = facts.stream().sorted(Comparator.comparing(PerfMetrics.SourceRow::key))
                .map(PerfCycleEvidence::row).toList();
        parents(jdbc, company, rows, "contract", "SELECT id, customer_id FROM fr_sale_contract", "customerId");
        parents(jdbc, company, rows, "commission", "SELECT id, customer_id FROM fr_commission", "customerId");
        parents(jdbc, company, rows, "node", "SELECT id, project_id FROM fr_eng_plan_node", "projectId");
        parents(jdbc, company, rows, "material", "SELECT id, project_id,supply_id FROM fr_eng_supply_receipt", "projectId");
        return rows;
    }

    private static Map<String, Object> row(PerfMetrics.SourceRow fact) {
        String key = fact.key();
        String prefix = key.substring(0, key.lastIndexOf(':'));
        String type = switch (prefix) {
            case "fin-receipt", "fin-reg-receipt" -> "receipt";
            case "fin-reg-payment" -> "payment";
            case "fin-refund" -> "refund";
            case "sale-contract" -> "contract";
            case "perf-node", "perf-node-ontime" -> "node";
            case "perf-material" -> "material";
            case "perf-publication" -> "publication";
            case "perf-lead" -> "lead";
            case "perf-commission", "perf-commission-approved" -> "commission";
            case "perf-follow-customer" -> "customer";
            default -> "other";
        };
        String reference = "commission".equals(type) ? fact.businessId() : key;
        String id = reference.substring(reference.lastIndexOf(':') + 1);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("type", fact.type());
        row.put("value", String.valueOf(fact.value()));
        row.put("date", fact.date().toString());
        row.put("businessId", fact.businessId());
        row.put("sourceType", type);
        row.put("sourceId", id);
        if ("customer".equals(type)) row.put("customerId", id);
        return row;
    }

    private static void parents(NamedParameterJdbcTemplate jdbc, long company, List<Map<String, Object>> rows,
                                String type, String select, String parentField) {
        Map<Long, List<Map<String, Object>>> byId = new LinkedHashMap<>();
        for (var row : rows) {
            if (type.equals(row.get("sourceType"))) {
                byId.computeIfAbsent(Long.valueOf((String) row.get("sourceId")), id -> new ArrayList<>()).add(row);
            }
        }
        if (byId.isEmpty()) return;
        jdbc.query(select + " WHERE company_id=:company AND id IN (:ids)",
                Map.of("company", company, "ids", byId.keySet()), (RowCallbackHandler) rs -> {
                    for (var row : byId.get(rs.getLong(1))) {
                        if (rs.getString(2) != null) row.put(parentField, rs.getString(2));
                        if ("material".equals(type) && rs.getString(3) != null) row.put("supplyId", rs.getString(3));
                    }
                });
    }
}
