package cn.iocoder.yudao.module.franchise.crm.workflow;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.CustomerWorkflowFactsPort;
import cn.iocoder.yudao.module.franchise.crm.CustomerAccess;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowModel.*;

/** Pure query projection. Reading never creates a workflow or marks any manual step complete. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CustomerWorkflowReadService {
    static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    static final TypeReference<List<Attachment>> ATTACHMENTS = new TypeReference<>() { };

    record WorkflowRow(long id, long tenantId, long companyId, long customerId, String status,
                       long revision, OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
    record StepRow(String code, String status, long revision, LocalDate occurredOn, String note,
                   Map<String, Object> fields, List<Attachment> attachments, OffsetDateTime completedAt) { }
    private record Projection(WorkflowRow workflow, List<StepView> steps, Summary summary) { }

    private final JdbcTemplate jdbc;
    private final ObjectProvider<CustomerWorkflowFactsPort> factProviders;
    private final SystemDirectory directory;

    public CustomerWorkflowReadService(JdbcTemplate jdbc, ObjectProvider<CustomerWorkflowFactsPort> factProviders,
                                      SystemDirectory directory) {
        this.jdbc = jdbc;
        this.factProviders = factProviders;
        this.directory = directory;
    }

    public View read(PostSnapshot post, CustomerDO customer) {
        if (!CustomerAccess.canRead(post, customer)) throw missing();
        long tenant = tenant();
        requireTenantCompany(tenant, customer.getCompanyId());
        return view(customer, projections(tenant, List.of(customer), CustomerAccess.canWrite(post, customer)).get(customer.getId()));
    }

    /** HQ controller must authorize HQ reader first. No synthetic store post and no global SQL permission bypass. */
    public View readAuthorized(long tenantId, long companyId, long customerId) {
        requireContext(tenantId);
        CustomerDO customer = authorizedCustomer(tenantId, companyId, customerId);
        return view(customer, projections(tenantId, List.of(customer), false).get(customerId));
    }

    /** Only already-authorized rows from the ordinary customer query are accepted here. */
    public Map<Long, Summary> summaries(PostSnapshot post, List<CustomerDO> customers) {
        if (customers.isEmpty()) return Map.of();
        if (customers.size() > 100) throw new ServiceException(400, "每次最多读取100位客户的进度");
        long tenant = tenant();
        for (CustomerDO customer : customers) {
            if (!CustomerAccess.canRead(post, customer)) throw missing();
        }
        for (long company : customers.stream().map(CustomerDO::getCompanyId).distinct().toList()) requireTenantCompany(tenant, company);
        return projections(tenant, customers, false).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, item -> item.getValue().summary()));
    }

    public Map<Long, Summary> summariesAuthorized(long tenantId, long companyId, List<Long> customerIds) {
        requireContext(tenantId);
        if (customerIds == null || customerIds.size() > 100) throw new ServiceException(400, "客户数量无效");
        requireTenantCompany(tenantId, companyId);
        if (customerIds.isEmpty()) return Map.of();
        var unique = customerIds.stream().distinct().toList();
        List<Object> params = new ArrayList<>(List.of(companyId)); params.addAll(unique);
        List<CustomerDO> rows = jdbc.query("SELECT id,company_id,name,owner_user_id,team_id FROM fr_customer WHERE company_id=? AND id IN ("
                + placeholders(unique.size()) + ")", (rs, n) -> customer(rs), params.toArray());
        if (rows.size() != unique.size()) throw missing();
        return projections(tenantId, rows, false).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, item -> item.getValue().summary()));
    }

    public PageResult<RecordView> recordsAuthorized(long tenantId, long companyId, long customerId, int pageNo, int pageSize) {
        requireContext(tenantId);
        authorizedCustomer(tenantId, companyId, customerId);
        if (pageNo < 1 || pageNo > 1_000_000 || pageSize < 1 || pageSize > 100)
            throw new ServiceException(400, "分页参数无效");
        WorkflowRow workflow = latest(tenantId, companyId, customerId);
        if (workflow == null) return new PageResult<>(List.of(), 0L);
        Long total = jdbc.queryForObject("SELECT count(*) FROM fr_customer_workflow_event WHERE tenant_id=? AND company_id=? AND workflow_id=?",
                Long.class, tenantId, companyId, workflow.id());
        return new PageResult<>(records(workflow, pageSize, (pageNo - 1L) * pageSize), total == null ? 0 : total);
    }

    WorkflowRow latest(long tenant, long company, long customer) {
        var rows = jdbc.query("SELECT * FROM fr_customer_workflow WHERE tenant_id=? AND company_id=? AND customer_id=? ORDER BY created_at DESC,id DESC LIMIT 1",
                CustomerWorkflowReadService::workflow, tenant, company, customer);
        return rows.isEmpty() ? null : rows.get(0);
    }

    StepRow step(long tenant, long company, long workflow, Step code) {
        var rows = jdbc.query("SELECT * FROM fr_customer_workflow_step WHERE tenant_id=? AND company_id=? AND workflow_id=? AND step_code=?",
                CustomerWorkflowReadService::step, tenant, company, workflow, code.name());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<Long, Projection> projections(long tenant, List<CustomerDO> customers, boolean writable) {
        if (customers.isEmpty()) return Map.of();
        List<Long> ids = customers.stream().map(CustomerDO::getId).toList();
        Map<Long, Long> companies = customers.stream().collect(Collectors.toMap(CustomerDO::getId, CustomerDO::getCompanyId));
        List<Object> parameters = new ArrayList<>(List.of(tenant)); parameters.addAll(ids);
        Map<Long, WorkflowRow> workflows = new LinkedHashMap<>();
        jdbc.query("SELECT * FROM fr_customer_workflow WHERE tenant_id=? AND customer_id IN (" + placeholders(ids.size())
                + ") ORDER BY created_at DESC,id DESC", CustomerWorkflowReadService::workflow, parameters.toArray())
                .forEach(row -> { if (Objects.equals(companies.get(row.customerId()), row.companyId())) workflows.putIfAbsent(row.customerId(), row); });
        Map<Long, Map<String, StepRow>> saved = new LinkedHashMap<>();
        if (!workflows.isEmpty()) {
            var workflowIds = workflows.values().stream().map(WorkflowRow::id).toList();
            List<Object> args = new ArrayList<>(List.of(tenant)); args.addAll(workflowIds);
            jdbc.query("SELECT * FROM fr_customer_workflow_step WHERE tenant_id=? AND workflow_id IN (" + placeholders(workflowIds.size()) + ")",
                    rs -> { long id = rs.getLong("workflow_id"); saved.computeIfAbsent(id, ignored -> new LinkedHashMap<>()).put(rs.getString("step_code"), step(rs, 0)); }, args.toArray());
        }
        Map<Long, Map<String, CustomerWorkflowFactsPort.Fact>> facts = new LinkedHashMap<>();
        factProviders.orderedStream().forEach(provider -> provider.findFacts(tenant, companies).forEach(fact -> {
            if (!Objects.equals(companies.get(fact.customerId()), fact.companyId()) || Step.parse(fact.stepCode()).manual())
                throw new IllegalStateException("Invalid workflow source fact scope");
            var prior = facts.computeIfAbsent(fact.customerId(), ignored -> new LinkedHashMap<>()).putIfAbsent(fact.stepCode(), fact);
            if (prior != null) throw new IllegalStateException("Ambiguous workflow source fact");
        }));
        Map<Long, Projection> result = new LinkedHashMap<>();
        for (CustomerDO customer : customers) {
            WorkflowRow workflow = workflows.get(customer.getId());
            Map<String, StepRow> rows = workflow == null ? Map.of() : saved.getOrDefault(workflow.id(), Map.of());
            Map<String, CustomerWorkflowFactsPort.Fact> source = facts.getOrDefault(customer.getId(), Map.of());
            List<StepView> steps = new ArrayList<>();
            boolean active = workflow == null || "ACTIVE".equals(workflow.status());
            for (Step code : Step.values()) {
                if (code.manual()) {
                    StepRow row = rows.get(code.name());
                    if (code == Step.SITE_VISIT && row != null && "COMPLETED".equals(row.status()) && row.fields().get("visitSkip") instanceof Map<?,?> skip) {
                        steps.add(new StepView(code.name(), code.label, code.ordinal() + 1, "SKIPPED", row.revision(),
                                null, String.valueOf(skip.get("reason")), Map.of("visitSkip", skip), List.of(), row.completedAt(),
                                false, false, writable && active, List.of(), "MANUAL"));
                        continue;
                    }
                    List<String> errors = CustomerWorkflowRules.missing(code, row == null ? null : row.occurredOn(),
                            row == null ? Map.of() : row.fields(), row == null ? List.of() : row.attachments());
                    boolean completed = row != null && "COMPLETED".equals(row.status());
                    steps.add(new StepView(code.name(), code.label, code.ordinal() + 1, row == null ? "TODO" : row.status(),
                            row == null ? 0 : row.revision(), row == null ? null : row.occurredOn(), row == null ? "" : row.note(),
                            row == null ? Map.of() : row.fields(), row == null ? List.of() : row.attachments(), row == null ? null : row.completedAt(),
                            writable && active && !completed, writable && active && !completed && errors.isEmpty(),
                            writable && active && completed, errors, "MANUAL"));
                } else {
                    var fact = source.get(code.name());
                    List<String> errors = fact == null ? List.of(code == Step.CONTRACT ? "等待正式施工合同签署记录" : "等待工程实际开工记录") : fact.missingEvidence();
                    boolean completed = fact != null && fact.complete();
                    steps.add(new StepView(code.name(), code.label, code.ordinal() + 1,
                            fact == null ? "TODO" : completed ? "COMPLETED" : "DRAFT", 0,
                            fact == null ? null : fact.occurredOn(), "", fact == null ? Map.of() : fact.fields(), List.of(),
                            null, // source domains store an actual date, not a fabricated completion timestamp
                            false, false, false, errors, code == Step.CONTRACT ? "CONTRACT" : "ENGINEERING"));
                }
            }
            List<StepView> remaining = steps.stream().filter(step -> !"COMPLETED".equals(step.status()) && !"SKIPPED".equals(step.status())).toList();
            int completed = 8 - remaining.size();
            String status = !active ? workflow.status() : completed == 8 ? "COMPLETED" : "ACTIVE";
            var summary = new Summary(workflow == null ? null : Long.toString(workflow.id()), completed, 8,
                    BigDecimal.valueOf(completed * 125L, 1), remaining.isEmpty() ? null : remaining.get(0).code(),
                    remaining.isEmpty() ? "全部完成" : remaining.get(0).name(), remaining.size() < 2 ? null : remaining.get(1).code(),
                    status, workflow == null ? null : workflow.updatedAt(),
                    rows.containsKey(Step.INVITATION.name()) ? (String) rows.get(Step.INVITATION.name()).fields().get("nextContactOn") : null);
            result.put(customer.getId(), new Projection(workflow, List.copyOf(steps), summary));
        }
        return result;
    }

    private View view(CustomerDO customer, Projection projection) {
        WorkflowRow workflow = projection.workflow(); Summary summary = projection.summary();
        List<RecordView> events = workflow == null ? List.of() : records(workflow, 21, 0);
        boolean more = events.size() > 20;
        return new View(summary.workflowId(), customer.getId().toString(), customer.getName(), customer.getCompanyId().toString(),
                1, summary.status(), workflow == null ? 0 : workflow.revision(), summary.completedCount(), 8, summary.progressPercent(),
                summary.currentStepCode(), summary.nextStepCode(), projection.steps().stream().anyMatch(step -> step.canWrite() || step.canReopen()),
                projection.steps(), more ? events.subList(0, 20) : events, more);
    }

    private List<RecordView> records(WorkflowRow workflow, int limit, long offset) {
        List<RecordView> records = jdbc.query("SELECT * FROM fr_customer_workflow_event WHERE tenant_id=? AND company_id=? AND workflow_id=? ORDER BY revision DESC LIMIT ? OFFSET ?",
                (rs, n) -> new RecordView(Long.toString(rs.getLong("id")), rs.getString("step_code"), rs.getString("action"),
                        rs.getObject("occurred_on", LocalDate.class), rs.getString("note"), rs.getObject("created_at", OffsetDateTime.class),
                        Long.toString(rs.getLong("actor_user_id")), "", rs.getLong("revision"),
                        JsonUtils.parseObject(rs.getString("fields"), MAP), JsonUtils.parseObject(rs.getString("attachments"), ATTACHMENTS)),
                workflow.tenantId(), workflow.companyId(), workflow.id(), limit, offset);
        if (records.isEmpty()) return records;
        Map<Long, String> names = new LinkedHashMap<>();
        directory.usersByIds(records.stream().map(record -> Long.parseLong(record.actorUserId())).distinct().toList())
                .forEach(user -> names.put(user.userId(), user.name() == null ? "" : user.name()));
        return records.stream().map(record -> {
            String actor = names.getOrDefault(Long.parseLong(record.actorUserId()), "");
            String type = record.type();
            if ("SITE_VISIT".equals(record.stepCode()) && "STATUS".equals(type) && record.fields().get("visitSkip") instanceof Map<?,?> skip) {
                type = "SKIP";
                if (skip.get("actorName") instanceof String original && !original.isBlank()) actor = original;
            }
            return new RecordView(record.id(), record.stepCode(), type, record.occurredOn(), record.note(),
                    record.createdAt(), record.actorUserId(), actor, record.revision(), record.fields(), record.attachments());
        }).toList();
    }

    private CustomerDO authorizedCustomer(long tenant, long company, long customerId) {
        requireTenantCompany(tenant, company);
        var rows = jdbc.query("SELECT id,company_id,name,owner_user_id,team_id FROM fr_customer WHERE id=? AND company_id=?",
                (rs, n) -> customer(rs), customerId, company);
        if (rows.size() != 1) throw missing();
        return rows.get(0);
    }
    private static CustomerDO customer(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CustomerDO().setId(rs.getLong("id")).setCompanyId(rs.getLong("company_id")).setName(rs.getString("name"))
                .setOwnerUserId(rs.getLong("owner_user_id")).setTeamId(rs.getObject("team_id", Long.class));
    }
    static WorkflowRow workflow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new WorkflowRow(rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("company_id"), rs.getLong("customer_id"),
                rs.getString("status"), rs.getLong("revision"), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }
    static StepRow step(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new StepRow(rs.getString("step_code"), rs.getString("status"), rs.getLong("revision"), rs.getObject("occurred_on", LocalDate.class),
                rs.getString("note"), JsonUtils.parseObject(rs.getString("fields"), MAP), JsonUtils.parseObject(rs.getString("attachments"), ATTACHMENTS),
                rs.getObject("completed_at", OffsetDateTime.class));
    }
    void requireTenantCompany(long tenant, long company) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM system_dept d JOIN pf_org_unit o ON o.dept_id=d.id"
                + " WHERE d.id=? AND d.tenant_id=? AND d.status=0 AND d.deleted=0 AND o.kind='ORG' AND o.status='ACTIVE')",
                Boolean.class, company, tenant))) throw missing();
    }
    static void requireContext(long tenant) { if (tenant != tenant()) throw missing(); }
    static long tenant() {
        Long tenant = TenantContextHolder.getTenantId();
        if (tenant == null) throw new ServiceException(401, "缺少有效租户身份");
        return tenant;
    }
    static String placeholders(int size) { return String.join(",", java.util.Collections.nCopies(size, "?")); }
    static ServiceException missing() { return new ServiceException(404, "客户流程不存在或无权查看"); }
}
