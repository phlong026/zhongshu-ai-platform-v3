package cn.iocoder.yudao.module.franchise.crm.workflow;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.CustomerWorkflowQuotePort;
import cn.iocoder.yudao.module.franchise.api.CustomerWorkflowReferencePort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.franchise.crm.CustomerAccess;
import cn.iocoder.yudao.module.franchise.crm.CustomerService;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommandKeys;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowModel.*;
import static cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowRules.invalid;

/**
 * Serialized commands on the customer row; source-of-truth contract and engineering facts remain read-only.
 * 八步流程是客户跟进的唯一事实：客户表上的最近跟进时间、下次跟进日期和“已跟进”状态只由本类同步，
 * 首页、待办和列表筛选读取那份投影，不再各自解释跟进记录。
 */
@Service
public class CustomerWorkflowService {
    private final CustomerService customers;
    private final CustomerMapper customerMapper;
    private final CustomerWorkflowReadService reads;
    private final JdbcTemplate jdbc;
    private final FranchiseCommands commands;
    private final ObjectProvider<AssetAccessPort> assets;
    private final ObjectProvider<StoreServiceAccountPort> accounts;
    private final ObjectProvider<CustomerWorkflowReferencePort> references;
    private final ObjectProvider<CustomerWorkflowQuotePort> quotes;
    private final CustomerWorkflowAssetPolicy attachmentPolicy;
    private final SystemDirectory directory;

    public CustomerWorkflowService(CustomerService customers, CustomerMapper customerMapper, CustomerWorkflowReadService reads,
                                   JdbcTemplate jdbc, FranchiseCommands commands, ObjectProvider<AssetAccessPort> assets,
                                   ObjectProvider<StoreServiceAccountPort> accounts, ObjectProvider<CustomerWorkflowReferencePort> references,
                                   ObjectProvider<CustomerWorkflowQuotePort> quotes, CustomerWorkflowAssetPolicy attachmentPolicy,
                                   SystemDirectory directory) {
        this.customers = customers; this.customerMapper = customerMapper; this.reads = reads; this.jdbc = jdbc;
        this.commands = commands; this.assets = assets; this.accounts = accounts; this.references = references;
        this.quotes = quotes; this.attachmentPolicy = attachmentPolicy;
        this.directory = directory;
    }

    public View get(PostSnapshot post, long customerId) { return reads.read(post, customers.requireReadable(post, customerId)); }

    public Map<Long, Summary> summaries(PostSnapshot post, List<Long> customerIds) {
        if (customerIds == null || customerIds.size() > 100 || customerIds.stream().anyMatch(id -> id == null || id <= 0))
            throw invalid("每次最多查询100个有效客户编号");
        var ids = customerIds.stream().distinct().toList();
        if (ids.isEmpty()) return Map.of();
        var query = new LambdaQueryWrapperX<CustomerDO>(); CustomerAccess.visibleTo(post).accept(query);
        query.in(CustomerDO::getId, ids);
        List<CustomerDO> rows = customerMapper.selectList(query);
        if (rows.size() != ids.size()) throw CustomerWorkflowReadService.missing();
        return reads.summaries(post, rows);
    }

    public PageResult<RecordView> records(PostSnapshot post, long customerId, int pageNo, int pageSize) {
        CustomerDO customer = customers.requireReadable(post, customerId);
        return reads.recordsAuthorized(CustomerWorkflowReadService.tenant(), customer.getCompanyId(), customerId, pageNo, pageSize);
    }

    /** 跟进时间线：八步的步骤记录与状态变更、补充记录、升级前的普通跟进，按时间倒序。 */
    public PageResult<RecordView> timeline(PostSnapshot post, long customerId, int pageNo, int pageSize) {
        CustomerDO customer = customers.requireReadable(post, customerId);
        return reads.timelineAuthorized(CustomerWorkflowReadService.tenant(), customer.getCompanyId(), customerId, pageNo, pageSize);
    }

    @Transactional(rollbackFor = Exception.class)
    public View save(PostSnapshot post, long customerId, String code, SaveCommand request, String key) {
        Step step = manual(code);
        SaveCommand normalized = CustomerWorkflowRules.normalize(step, request);
        Map<String, Object> inputFields = new LinkedHashMap<>(normalized.fields());
        inputFields.remove("referenceCaseName"); // the catalog, not the caller, supplies the persisted display name
        // 只有请求里明确的 nextFollowOn 才改客户的下次跟进日期。第 1 步升级前的“下次联系”是八步自己的日期，
        // 不并入（与小程序 PR #287 一致：客户列表的提醒只认客户的下次跟进日期）。
        SaveCommand input = new SaveCommand(normalized.revision(), normalized.occurredOn(), normalized.note(), inputFields, normalized.attachments(), normalized.nextFollowOn());
        var fingerprint = new Mutation("SAVE", customerId, step.name(), input);
        CustomerDO customer = lockWritable(post, customerId, key);
        if (commands.replayed(post, key, fingerprint).isPresent()) return reads.read(post, refreshed(customer));
        var workflow = requireWorkflow(customer, input.revision(), true);
        requireActive(workflow);
        var previous = reads.step(workflow.tenantId(), workflow.companyId(), workflow.id(), step);
        if (previous != null && "COMPLETED".equals(previous.status())) throw conflict("请先重开已完成步骤，再补充或更正资料");
        // Pre-link mini programs omit quoteId entirely. The current external-quote form sends an explicit blank.
        if (step == Step.QUOTATION && previous != null && previous.fields().containsKey("quoteId")
                && !input.fields().containsKey("quoteId")
                && !(request.fields() != null && request.fields().get("quoteId") instanceof String submitted && submitted.isBlank())) {
            throw invalid("本步骤已关联正式报价，请更新小程序后再修改");
        }
        // Legacy clients omit the mode; only an explicit switch can replace a recorded custom direction.
        if (step == Step.HOUSE_SELECTION && previous != null && "EXTERNAL_CUSTOM".equals(previous.fields().get("selectionMode"))
                && !input.fields().containsKey("selectionMode")
                && !(request.fields() != null && request.fields().get("selectionMode") instanceof String submitted && submitted.isBlank())) {
            throw invalid("本步骤已记录外部定制方向，请更新小程序后再修改");
        }
        // 专用资料槽位（05 设计合同、06 设计文件）存在 fields 里；旧版小程序不知道这些键，
        // 整字段替换会静默清掉已登记的文件引用，所以沿用 quoteId 的守卫：漏发即拒绝，只有显式空值才能清除。
        for (String slotKey : CustomerWorkflowRules.SLOT_ASSET_FIELDS) {
            if (!slotAssetStep(slotKey).equals(step) || previous == null
                    || !previous.fields().containsKey(slotKey) || input.fields().containsKey(slotKey)
                    || request.fields() != null && request.fields().get(slotKey) instanceof String submitted && submitted.isBlank()) continue;
            throw invalid("本步骤已登记" + (slotKey.equals("designContractAssetId") ? "设计合同" : "设计文件") + "，请更新小程序后再修改");
        }
        Map<String, Object> fields = referencedFields(post, customerId, step, input.fields());
        validateAttachments(post, customerId, input.attachments());
        validateSlotAssets(post, customerId, step, fields);
        if (step == Step.MEASUREMENT) {
            CustomerWorkflowRules.applyMeasurementSiteFields(fields, previous == null ? Map.of() : previous.fields());
        }
        long stepRevision = previous == null ? 1 : previous.revision() + 1;
        jdbc.update("""
                INSERT INTO fr_customer_workflow_step(workflow_id,tenant_id,company_id,step_code,status,revision,occurred_on,note,fields,attachments,next_follow_on)
                VALUES(?,?,?,?,'DRAFT',?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?)
                ON CONFLICT(workflow_id,step_code) DO UPDATE SET status='DRAFT',revision=EXCLUDED.revision,
                    occurred_on=EXCLUDED.occurred_on,note=EXCLUDED.note,fields=EXCLUDED.fields,attachments=EXCLUDED.attachments,
                    next_follow_on=EXCLUDED.next_follow_on,completed_at=NULL,updated_at=CURRENT_TIMESTAMP
                """, workflow.id(), workflow.tenantId(), workflow.companyId(), step.name(), stepRevision,
                input.occurredOn(), input.note(), JsonUtils.toJsonString(fields), JsonUtils.toJsonString(input.attachments()), input.nextFollowOn());
        record(post, workflow, step, "SAVE", input.occurredOn(), input.note(), fields, input.attachments(), input.nextFollowOn(), fingerprint, key);
        touch(customer, input.nextFollowOn());
        return reads.read(post, refreshed(customer));
    }

    /**
     * 完成本步沿用这条记录约定的下次跟进日期。是否必填目前由小程序把关：已发布的八步页版本不带该字段，
     * 服务端此时强制会让它无法完成步骤；旧版本退出后在清理批改为服务端必填。
     */
    @Transactional(rollbackFor = Exception.class)
    public View complete(PostSnapshot post, long customerId, String code, long revision, String key) {
        Step step = manual(code);
        var fingerprint = new Mutation("COMPLETE", customerId, step.name(), new RevisionCommand(revision));
        CustomerDO customer = lockWritable(post, customerId, key);
        if (commands.replayed(post, key, fingerprint).isPresent()) return reads.read(post, refreshed(customer));
        var workflow = requireWorkflow(customer, revision, false); requireActive(workflow);
        var draft = reads.step(workflow.tenantId(), workflow.companyId(), workflow.id(), step);
        if (draft == null) throw invalid("请先保存本步骤的工作记录");
        if ("COMPLETED".equals(draft.status())) throw conflict("本步骤已经完成");
        List<String> errors = CustomerWorkflowRules.missing(step, draft.occurredOn(), draft.fields(), draft.attachments());
        if (!errors.isEmpty()) throw invalid(String.join("；", errors));
        Map<String, Object> source = referencedFields(post, customerId, step, draft.fields());
        if (step == Step.QUOTATION && !source.equals(draft.fields())) throw conflict("报价原单已更新，请重新选择核对后再完成");
        validateAttachments(post, customerId, draft.attachments());
        jdbc.update("UPDATE fr_customer_workflow_step SET status='COMPLETED',revision=revision+1,completed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP"
                + " WHERE tenant_id=? AND company_id=? AND workflow_id=? AND step_code=?", workflow.tenantId(), workflow.companyId(), workflow.id(), step.name());
        record(post, workflow, step, "COMPLETE", draft.occurredOn(), draft.note(), draft.fields(), draft.attachments(), draft.nextFollowOn(), fingerprint, key);
        touch(customer, draft.nextFollowOn());
        return reads.read(post, refreshed(customer));
    }

    @Transactional(rollbackFor = Exception.class)
    public View skipVisit(PostSnapshot post, long customerId, SkipVisitCommand request, String key) {
        if (request == null) throw invalid("请填写跳过带看原因");
        String reason = CustomerWorkflowRules.text(request.reason(), 1000);
        if (reason.isBlank()) throw invalid("请填写跳过带看原因");
        var fingerprint = new Mutation("SKIP", customerId, Step.SITE_VISIT.name(),
                Map.of("revision", request.revision(), "reason", reason, "actorPostId", post.postId()));
        CustomerDO customer = lockWritable(post, customerId, key);
        if (commands.replayed(post, key, fingerprint).isPresent()) return reads.read(post, refreshed(customer));
        var workflow = requireWorkflow(customer, request.revision(), true);
        requireActive(workflow);
        var prior = reads.step(workflow.tenantId(), workflow.companyId(), workflow.id(), Step.SITE_VISIT);
        if (prior != null && "COMPLETED".equals(prior.status())) throw conflict("带看已办理，如需更正请先重开");
        String actorName = directory.usersByIds(List.of(post.userId())).stream().filter(user -> user.userId() == post.userId())
                .map(SystemDirectory.UserBrief::name).filter(java.util.Objects::nonNull).findFirst().orElse("");
        var evidence = Map.<String,Object>of("visitSkip", Map.of("reason", reason, "actorUserId", Long.toString(post.userId()),
                "actorPostId", Long.toString(post.postId()), "actorName", actorName));
        // Keep the previous visit draft available for a deliberate reopen. The skip has its own source and no visit date.
        jdbc.update("""
                INSERT INTO fr_customer_workflow_step(workflow_id,tenant_id,company_id,step_code,status,revision,fields,completed_at)
                VALUES(?,?,?,'SITE_VISIT','COMPLETED',1,CAST(? AS jsonb),CURRENT_TIMESTAMP)
                ON CONFLICT(workflow_id,step_code) DO UPDATE SET status='COMPLETED',revision=fr_customer_workflow_step.revision+1,
                    fields=fr_customer_workflow_step.fields || EXCLUDED.fields,completed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
                """, workflow.id(), workflow.tenantId(), workflow.companyId(), JsonUtils.toJsonString(evidence));
        record(post, workflow, Step.SITE_VISIT, "SKIP", null, reason, evidence, List.of(), null, fingerprint, key);
        touch(customer, null);
        return reads.read(post, refreshed(customer));
    }

    @Transactional(rollbackFor = Exception.class)
    public View reopen(PostSnapshot post, long customerId, String code, ReopenCommand request, String key) {
        Step step = manual(code);
        if (request == null) throw invalid("请填写重开原因");
        var input = new ReopenCommand(request.revision(), CustomerWorkflowRules.reason(request.reason()));
        var fingerprint = new Mutation("REOPEN", customerId, step.name(), input);
        CustomerDO customer = lockWritable(post, customerId, key);
        if (commands.replayed(post, key, fingerprint).isPresent()) return reads.read(post, refreshed(customer));
        var workflow = requireWorkflow(customer, input.revision(), false); requireActive(workflow);
        var saved = reads.step(workflow.tenantId(), workflow.companyId(), workflow.id(), step);
        if (saved == null || !"COMPLETED".equals(saved.status())) throw conflict("只有已完成的步骤可以重开");
        jdbc.update("UPDATE fr_customer_workflow_step SET status='DRAFT',revision=revision+1,completed_at=NULL,fields=fields-'visitSkip',updated_at=CURRENT_TIMESTAMP"
                + " WHERE tenant_id=? AND company_id=? AND workflow_id=? AND step_code=?", workflow.tenantId(), workflow.companyId(), workflow.id(), step.name());
        var fields = new LinkedHashMap<>(saved.fields());
        fields.remove("visitSkip");
        record(post, workflow, step, "REOPEN", saved.occurredOn(), input.reason(), fields, saved.attachments(), saved.nextFollowOn(), fingerprint, key);
        touch(customer, null);
        return reads.read(post, refreshed(customer));
    }

    /**
     * 流失：必选原因，“其他”须自填说明，约定日期清空，不再进入待跟进。
     * 暂缓：必填原因和晚于今天的再联系日期，到期自动回到待跟进。恢复：下次跟进日期落到今天。
     */
    @Transactional(rollbackFor = Exception.class)
    public View status(PostSnapshot post, long customerId, StatusCommand request, String key) {
        if (request == null || request.status() == null || !Set.of("ACTIVE", "PAUSED", "LOST").contains(request.status())) throw invalid("流程状态无效");
        LocalDate today = CustomerWorkflowRules.today();
        String status = request.status();
        String code = request.reasonCode() == null || request.reasonCode().isBlank() ? null : request.reasonCode().trim();
        String reason = CustomerWorkflowRules.text(request.reason(), 1000);
        LocalDate nextFollowOn = request.nextFollowOn();
        if (status.equals("LOST")) {
            if (code == null || !LOST_REASONS.containsKey(code)) throw invalid("请选择流失原因");
            if (reason.isBlank()) {
                if (code.equals("OTHER")) throw invalid("请填写流失原因");
                reason = LOST_REASONS.get(code);
            }
            nextFollowOn = null;
        } else if (status.equals("PAUSED")) {
            if (reason.isBlank()) throw invalid("请填写暂缓原因");
            if (nextFollowOn == null || !nextFollowOn.isAfter(today)) throw invalid("请选择晚于今天的再联系日期");
            code = null;
        } else {
            code = null;
            nextFollowOn = today;
        }
        StatusCommand input = new StatusCommand(request.revision(), status, code, reason, nextFollowOn);
        var fingerprint = new Mutation("STATUS", customerId, null, input);
        CustomerDO customer = lockWritable(post, customerId, key);
        if (commands.replayed(post, key, fingerprint).isPresent()) return reads.read(post, refreshed(customer));
        var workflow = requireWorkflow(customer, input.revision(), true);
        if (workflow.status().equals(input.status())) throw conflict("流程已处于该状态");
        jdbc.update("UPDATE fr_customer_workflow SET status=?,status_reason_code=?,status_reason=?,reconsulted_at=NULL WHERE id=? AND tenant_id=? AND company_id=?",
                input.status(), input.reasonCode(), input.reason(), workflow.id(), workflow.tenantId(), workflow.companyId());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", input.status());
        if (input.reasonCode() != null) fields.put("reasonCode", input.reasonCode());
        fields.put("nextFollowOn", input.nextFollowOn());
        record(post, workflow, null, "STATUS", null, input.reason(), fields, List.of(), input.nextFollowOn(), fingerprint, key);
        if (status.equals("LOST")) {
            customerMapper.update(null, new LambdaUpdateWrapper<CustomerDO>().eq(CustomerDO::getId, customerId)
                    .set(CustomerDO::getNextVisitDate, null));
        }
        touch(customer, input.nextFollowOn());
        return reads.read(post, refreshed(customer));
    }

    CustomerDO lockWritable(PostSnapshot post, long customerId, String key) {
        commands.requireIdempotencyKey(key);
        CustomerDO before = customers.requireWritable(post, customerId);
        long tenant = CustomerWorkflowReadService.tenant();
        reads.requireTenantCompany(tenant, before.getCompanyId());
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class,
                FranchiseCommandKeys.lockKey(new FranchiseCommandKeys.PostKeyScope(FranchiseRoles.DOMAIN, post.userId()), key.trim()));
        CustomerDO locked = customerMapper.lockById(customerId);
        if (locked == null || !CustomerAccess.canWrite(post, locked)) throw CustomerWorkflowReadService.missing();
        return locked;
    }

    /**
     * 客资关联到老客户（登记人可见时自动关联，或负责人关联）：时间线上留一条“再次咨询”，并把下次跟进提到今天。
     * 已流失的客户不自动恢复，只记下再次咨询的时间，由负责人确认后恢复。调用方已持有客资命令的幂等与审计。
     */
    @Transactional(rollbackFor = Exception.class)
    public void recordConsult(PostSnapshot post, long customerId, long leadId, String need, String source) {
        if (TenantContextHolder.getTenantId() == null) return;
        CustomerDO customer = customerMapper.lockById(customerId);
        if (customer == null) return;
        long tenant = CustomerWorkflowReadService.tenant();
        reads.requireTenantCompany(tenant, customer.getCompanyId());
        var row = reads.latest(tenant, customer.getCompanyId(), customerId);
        if (row == null) {
            jdbc.update("INSERT INTO fr_customer_workflow(id,tenant_id,company_id,customer_id) VALUES(?,?,?,?)",
                    IdWorker.getId(), tenant, customer.getCompanyId(), customerId);
            row = reads.latest(tenant, customer.getCompanyId(), customerId);
        }
        long revision = row.revision() + 1;
        if (jdbc.update("UPDATE fr_customer_workflow SET revision=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND company_id=? AND revision=?",
                revision, row.id(), tenant, row.companyId(), row.revision()) != 1) throw conflict("流程已更新，请刷新后重试");
        LocalDate today = CustomerWorkflowRules.today();
        var fields = new LinkedHashMap<String, Object>();
        fields.put("leadId", Long.toString(leadId));
        fields.put("source", source == null ? "" : source);
        jdbc.update("""
                INSERT INTO fr_customer_workflow_event(id,workflow_id,tenant_id,company_id,step_code,action,revision,occurred_on,note,fields,attachments,actor_user_id,actor_post_id)
                VALUES(?,?,?,?,NULL,'CONSULT',?,?,?,CAST(? AS jsonb),'[]'::jsonb,?,?)
                """, IdWorker.getId(), row.id(), tenant, row.companyId(), revision, today, need == null ? "" : need,
                JsonUtils.toJsonString(fields), post.userId(), post.postId());
        if (row.status().equals("LOST")) {
            jdbc.update("UPDATE fr_customer_workflow SET reconsulted_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND company_id=?",
                    row.id(), tenant, row.companyId());
            return;
        }
        // 已逾期或没约定的提到今天；已经约好的未来日期由经办人自己决定是否提前。
        jdbc.update("UPDATE fr_customer SET next_visit_date=?,update_time=CURRENT_TIMESTAMP WHERE id=? AND (next_visit_date IS NULL OR next_visit_date>?)",
                today, customerId, today);
    }

    /** 写入后重读客户行：响应里的下次跟进日期、最近跟进时间必须是刚写入的投影，而不是加锁时的旧值。 */
    private CustomerDO refreshed(CustomerDO customer) { return customerMapper.selectById(customer.getId()); }

    /** 客户表上的跟进投影只在这里写：最近跟进时间与“已跟进”状态；只有带了日期才改下次跟进日期。 */
    void touch(CustomerDO customer, LocalDate nextFollowOn) {
        LambdaUpdateWrapper<CustomerDO> update = new LambdaUpdateWrapper<CustomerDO>()
                .eq(CustomerDO::getId, customer.getId())
                .set(CustomerDO::getLastFollowAt, OffsetDateTime.now())
                .set(CustomerDO::getUpdateTime, OffsetDateTime.now())
                .setSql("status = CASE WHEN status = 'REGISTERED' THEN 'FOLLOWING' ELSE status END");
        if (nextFollowOn != null) update.set(CustomerDO::getNextVisitDate, nextFollowOn);
        customerMapper.update(null, update);
    }

    private CustomerWorkflowReadService.WorkflowRow requireWorkflow(CustomerDO customer, long revision, boolean create) {
        if (revision < 0) throw invalid("流程版本无效");
        long tenant = CustomerWorkflowReadService.tenant();
        var row = reads.latest(tenant, customer.getCompanyId(), customer.getId());
        if (row == null && create && revision == 0) {
            long id = IdWorker.getId();
            jdbc.update("INSERT INTO fr_customer_workflow(id,tenant_id,company_id,customer_id) VALUES(?,?,?,?)",
                    id, tenant, customer.getCompanyId(), customer.getId());
            row = reads.latest(tenant, customer.getCompanyId(), customer.getId());
        }
        if (row == null) throw invalid("请先保存客户的跟进记录");
        if (row.revision() != revision) throw conflict("流程已更新，请刷新后重试");
        return row;
    }

    private Map<String, Object> referencedFields(PostSnapshot post, long customerId, Step step, Map<String, Object> fields) {
        Map<String, Object> normalized = new LinkedHashMap<>(fields);
        if (step == Step.QUOTATION && normalized.containsKey("quoteId")) {
            var port = quotes.getIfAvailable();
            if (port == null) throw new ServiceException(503, "正式报价校验暂不可用，请稍后重试");
            long id = CustomerWorkflowRules.integer(normalized.get("quoteId"), "quoteId");
            var quote = port.requireCustomerQuote(post, customerId, id);
            normalized.put("amountCents", Long.toString(quote.totalCents()));
            normalized.put("versionLabel", CustomerWorkflowRules.text(quote.versionLabel(), 1000));
        }
        if (step != Step.HOUSE_SELECTION || !normalized.containsKey("referenceCaseId")) return normalized;
        var referencePort = references.getIfAvailable();
        if (referencePort == null) throw new ServiceException(503, "灵感库校验暂不可用，请稍后重试");
        long id = CustomerWorkflowRules.integer(normalized.get("referenceCaseId"), "referenceCaseId");
        if (id <= 0) throw invalid("参考户型编号无效");
        var reference = referencePort.requirePublishedCase(post, id);
        if (reference == null || !Long.toString(id).equals(reference.id())) throw invalid("参考户型不可用");
        normalized.put("referenceCaseName", CustomerWorkflowRules.text(reference.name(), 1000));
        return normalized;
    }

    void validateAttachments(PostSnapshot post, long customerId, List<Attachment> attachments) {
        if (attachments.isEmpty()) return;
        attachmentPolicy.requireAttachable(post, customerId, attachments);
        var assetPort = assets.getIfAvailable(); var accountPort = accounts.getIfAvailable();
        if (assetPort == null || accountPort == null) throw new ServiceException(503, "资料校验暂不可用，请稍后重试");
        var actor = new AssetAccessPort.Actor(post.orgId(), accountPort.requireServiceAccount(post.orgId()), post.userId(), post.postId());
        for (Attachment attachment : attachments) {
            if (!assetPort.validateReference(actor, Long.parseLong(attachment.assetId()), Set.of("BUSINESS_DOCUMENT", "DESIGN_REFERENCE")))
                throw invalid("附件不存在、未上传完成或无权引用");
        }
    }

    /** 专用槽位（05 设计合同、06 设计文件）的 fields 引用与通用附件同一套约束：先锁归属，再验资产可用。 */
    private void validateSlotAssets(PostSnapshot post, long customerId, Step step, Map<String, Object> fields) {
        boolean any = CustomerWorkflowRules.SLOT_ASSET_FIELDS.stream()
                .anyMatch(key -> slotAssetStep(key).equals(step) && fields.get(key) != null);
        if (!any) return;
        var assetPort = assets.getIfAvailable(); var accountPort = accounts.getIfAvailable();
        if (assetPort == null || accountPort == null) throw new ServiceException(503, "资料校验暂不可用，请稍后重试");
        var actor = new AssetAccessPort.Actor(post.orgId(), accountPort.requireServiceAccount(post.orgId()), post.userId(), post.postId());
        for (String key : CustomerWorkflowRules.SLOT_ASSET_FIELDS) {
            if (!slotAssetStep(key).equals(step)) continue;
            Object value = fields.get(key);
            if (value == null) continue;
            long assetId = Long.parseLong(String.valueOf(value));
            attachmentPolicy.requireSlotAttachable(post, customerId, assetId);
            if (!assetPort.validateReference(actor, assetId, Set.of("BUSINESS_DOCUMENT", "DESIGN_REFERENCE")))
                throw invalid("资料不存在、未上传完成或无权引用");
        }
    }

    private static Step slotAssetStep(String slotKey) {
        return "designContractAssetId".equals(slotKey) ? Step.MEASUREMENT : Step.QUOTATION;
    }

    @Transactional(rollbackFor = Exception.class)
    public AssetAccessPort.DownloadUrlView download(PostSnapshot post, long customerId, String code, long assetId) {
        Step step = Step.parse(code);
        CustomerDO customer = customers.requireReadable(post, customerId);
        long tenant = CustomerWorkflowReadService.tenant(); reads.requireTenantCompany(tenant, customer.getCompanyId());
        attachmentPolicy.requireReferenced(tenant, customer.getCompanyId(), customerId, step.name(), assetId);
        var assetPort = assets.getIfAvailable(); var accountPort = accounts.getIfAvailable();
        if (assetPort == null || accountPort == null) throw new ServiceException(503, "资料下载暂不可用");
        var actor = new AssetAccessPort.Actor(post.orgId(), accountPort.requireServiceAccount(post.orgId()), post.userId(), post.postId());
        if (!assetPort.validateReference(actor, assetId, Set.of("BUSINESS_DOCUMENT", "DESIGN_REFERENCE"))) throw invalid("资料已失效或无权查看");
        var ticket = assetPort.createWorkflowDownloadUrl(actor, customerId, step.name(), assetId);
        commands.record(post, "fr-workflow-asset-read", assetId, IdWorker.getId(), "workflow-asset-download",
                Map.of("customerId", customerId, "stepCode", code, "assetId", assetId), "workflow-download:" + java.util.UUID.randomUUID());
        return ticket;
    }

    private void record(PostSnapshot post, CustomerWorkflowReadService.WorkflowRow workflow, Step step, String action,
                        java.time.LocalDate date, String note, Map<String, Object> fields, List<Attachment> attachments,
                        LocalDate nextFollowOn, Object fingerprint, String key) {
        long revision = workflow.revision() + 1;
        int updated = jdbc.update("UPDATE fr_customer_workflow SET revision=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND company_id=? AND revision=?",
                revision, workflow.id(), workflow.tenantId(), workflow.companyId(), workflow.revision());
        if (updated != 1) throw conflict("流程已更新，请刷新后重试");
        jdbc.update("""
                INSERT INTO fr_customer_workflow_event(id,workflow_id,tenant_id,company_id,step_code,action,revision,occurred_on,note,fields,attachments,next_follow_on,actor_user_id,actor_post_id)
                VALUES(?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?,?,?)
                """, IdWorker.getId(), workflow.id(), workflow.tenantId(), workflow.companyId(), step == null ? null : step.name(),
                "SKIP".equals(action) ? "STATUS" : action, revision, date, note, JsonUtils.toJsonString(fields), JsonUtils.toJsonString(attachments),
                nextFollowOn, post.userId(), post.postId());
        commands.record(post, "fr-customer-workflow", workflow.id(), revision, "workflow-" + action.toLowerCase(java.util.Locale.ROOT), fingerprint, key);
    }

    private static Step manual(String code) { Step step = Step.parse(code); if (!step.manual()) throw invalid("签约和开工步骤由正式业务记录更新"); return step; }
    private static void requireActive(CustomerWorkflowReadService.WorkflowRow row) { if (!"ACTIVE".equals(row.status())) throw conflict("请先恢复该客户的跟进流程"); }
    private static ServiceException conflict(String message) { return new ServiceException(409, message); }
    private record Mutation(String action, long customerId, String stepCode, Object body) { }
}
