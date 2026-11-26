package cn.iocoder.yudao.module.franchise.crm.workflow;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.crm.CustomerAccess;
import cn.iocoder.yudao.module.franchise.crm.CustomerService;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowModel.Attachment;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowModel.Step;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowRules.invalid;

@Service
public class CustomerWorkflowSupplementService {
    public record Input(String note, List<Attachment> attachments) {}
    public record Entry(String id, String customerId, String stepCode, String note, List<Attachment> attachments,
                        String actorUserId, String actorName, String createdAt) {}
    public record Entries(List<Entry> list, long total, boolean canWrite) {}
    private record Create(long customerId, String stepCode, long actorPostId, Input input) {}

    private static final String SCOPE = " FROM fr_customer_step_supplement WHERE tenant_id=? AND company_id=? AND customer_id=? AND step_code=?";
    private static final String AGGREGATE = "fr-customer-step-supplement";
    private final JdbcTemplate jdbc;
    private final CustomerService customers;
    private final CustomerWorkflowService workflows;
    private final CustomerWorkflowReadService reads;
    private final SystemDirectory directory;
    private final FranchiseCommands commands;

    public CustomerWorkflowSupplementService(JdbcTemplate jdbc, CustomerService customers, CustomerWorkflowService workflows,
            CustomerWorkflowReadService reads, SystemDirectory directory, FranchiseCommands commands) {
        this.jdbc = jdbc;
        this.customers = customers;
        this.workflows = workflows;
        this.reads = reads;
        this.directory = directory;
        this.commands = commands;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Entries list(PostSnapshot post, long customerId, String code, int pageNo, int pageSize) {
        Step step = Step.parse(code);
        if (pageNo < 1 || pageSize < 1 || pageSize > 200) throw invalid("附加资料分页参数无效");
        var customer = customers.requireReadable(post, customerId);
        long tenant = CustomerWorkflowReadService.tenant(), company = customer.getCompanyId();
        reads.requireTenantCompany(tenant, company);
        long total = jdbc.queryForObject("SELECT count(*)" + SCOPE, Long.class, tenant, company, customerId, step.name());
        var rows = jdbc.query("SELECT *" + SCOPE + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                CustomerWorkflowSupplementService::entry, tenant, company, customerId, step.name(), pageSize, ((long) pageNo - 1) * pageSize);
        return new Entries(rows, total, CustomerAccess.canWrite(post, customer));
    }

    @Transactional(rollbackFor = Exception.class)
    public Entry append(PostSnapshot post, long customerId, String code, Input body, String key) {
        Step step = Step.parse(code);
        if (body == null) throw invalid("请填写文字或选择附件");
        var input = new Input(CustomerWorkflowRules.text(body.note(), 4000), CustomerWorkflowRules.attachments(body.attachments()));
        if (input.note().isBlank() && input.attachments().isEmpty()) throw invalid("请填写文字或选择附件");

        var customer = workflows.lockWritable(post, customerId, key);
        long tenant = CustomerWorkflowReadService.tenant(), company = customer.getCompanyId();
        var fingerprint = new Create(customerId, step.name(), post.postId(), input);
        var prior = commands.replayed(post, key, fingerprint);
        if (prior.isPresent()) {
            var event = prior.get();
            if (!AGGREGATE.equals(event.getAggregateType())) throw exception(IDEMPOTENCY_KEY_REUSED);
            return jdbc.queryForObject("SELECT *" + SCOPE + " AND id=?", CustomerWorkflowSupplementService::entry,
                    tenant, company, customerId, step.name(), event.getAggregateId());
        }

        workflows.validateAttachments(post, customerId, input.attachments());
        String actorName = directory.usersByIds(List.of(post.userId())).stream()
                .filter(user -> user.userId() == post.userId()).map(SystemDirectory.UserBrief::name)
                .filter(Objects::nonNull).findFirst().orElse("");
        long id = IdWorker.getId();
        var entry = jdbc.queryForObject("""
                INSERT INTO fr_customer_step_supplement(id,tenant_id,company_id,customer_id,step_code,note,attachments,actor_user_id,actor_post_id,actor_name)
                VALUES(?,?,?,?,?,?,CAST(? AS jsonb),?,?,?) RETURNING *
                """, CustomerWorkflowSupplementService::entry, id, tenant, company, customerId, step.name(), input.note(),
                JsonUtils.toJsonString(input.attachments()), post.userId(), post.postId(), actorName);
        commands.record(post, AGGREGATE, id, 1, "customer-step-supplement", fingerprint, key);
        return entry;
    }

    private static Entry entry(ResultSet row, int index) throws SQLException {
        return new Entry(row.getString("id"), row.getString("customer_id"), row.getString("step_code"), row.getString("note"),
                JsonUtils.parseArray(row.getString("attachments"), Attachment.class), row.getString("actor_user_id"),
                row.getString("actor_name"), row.getObject("created_at", OffsetDateTime.class).toInstant().toString());
    }
}
