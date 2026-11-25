package cn.iocoder.yudao.module.franchise.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Read-only source facts. Callers must first authorize every customer; implementations still bind tenant/company. */
public interface CustomerWorkflowFactsPort {
    record Fact(long customerId, long companyId, String stepCode, LocalDate occurredOn,
                Map<String, Object> fields, boolean complete, List<String> missingEvidence) { }

    /** customerId -> persisted companyId; never accepts caller-supplied organization scope from HTTP. */
    List<Fact> findFacts(long tenantId, Map<Long, Long> customerCompanies);
}
