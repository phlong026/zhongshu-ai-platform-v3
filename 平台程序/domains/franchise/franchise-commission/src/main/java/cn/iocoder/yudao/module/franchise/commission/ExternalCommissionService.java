package cn.iocoder.yudao.module.franchise.commission;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.*;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionDO;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionMapper;
import cn.iocoder.yudao.module.franchise.crm.CustomerAccess;
import cn.iocoder.yudao.module.franchise.crm.CustomerService;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerMapper;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowAssetPolicy;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowModel.Attachment;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommandKeys;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.commission.ExternalCommissionModel.*;
import static cn.iocoder.yudao.module.franchise.commission.dal.CommissionDO.*;
import static cn.iocoder.yudao.module.franchise.commission.enums.FranchiseCommissionErrorCodes.HISTORICAL_SETTLEMENT_REQUIRED;

/** External registration is a business record. It creates neither internal design jobs nor payable/payment orders. */
@Service
public class ExternalCommissionService {
    private static final String AGGREGATE = "fr-external-commission";
    static final Set<String> CONVERTIBLE = Set.of(STATUS_FEE_PENDING, STATUS_FEE_RETURNED, STATUS_DESIGNING,
            STATUS_DESIGN_COMPLETED, STATUS_DISPATCHED, STATUS_HANDOVER_RETURNED);
    private static final TypeReference<List<Attachment>> FILES = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final CurrentPost posts;
    private final CustomerService customers;
    private final CustomerMapper customerMapper;
    private final CommissionMapper commissions;
    private final FranchiseCommands commands;
    private final CustomerPaymentPlanDraftPort plans;
    private final CustomerWorkflowAssetPolicy assetPolicy;
    private final AssetAccessPort assets;
    private final StoreServiceAccountPort accounts;
    private final ObjectProvider<AssetEvidenceQueryPort> evidence;
    private final ExternalCommissionMeasurements measurements;
    private final CommissionSimulatedPayments simulations;
    private final DesignFeeSettlementPort settlement;

    public ExternalCommissionService(JdbcTemplate jdbc, CurrentPost posts, CustomerService customers, CustomerMapper customerMapper,
            CommissionMapper commissions, FranchiseCommands commands, CustomerPaymentPlanDraftPort plans,
            CustomerWorkflowAssetPolicy assetPolicy, AssetAccessPort assets, StoreServiceAccountPort accounts,
            ObjectProvider<AssetEvidenceQueryPort> evidence, CommissionSimulatedPayments simulations, DesignFeeSettlementPort settlement) {
        this.jdbc=jdbc; this.posts=posts; this.customers=customers; this.customerMapper=customerMapper; this.commissions=commissions;
        this.commands=commands; this.plans=plans; this.assetPolicy=assetPolicy; this.assets=assets; this.accounts=accounts;
        this.evidence=evidence; this.simulations=simulations; this.settlement=settlement;
        this.measurements = new ExternalCommissionMeasurements(jdbc, evidence);
    }

    /** All reads are side-effect free; the plan port locks its real contract to return a coherent version. */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public View get(PostSnapshot post, long customerId) {
        CustomerDO customer = requireCustomer(post, customerId, false);
        CommissionDO commission = commissions.selectByCustomer(customerId);
        var plan = plans.snapshotForEntrust(post, customerId);
        return view(post, customer, commission, plan);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MeasurementPage measurements(PostSnapshot post, long customerId, Long before, int pageSize) {
        requireCustomer(post, customerId, false);
        return measurements.list(tenant(), post.orgId(), customerId, before, pageSize);
    }

    @Transactional(rollbackFor = Exception.class)
    public View register(PostSnapshot post, long customerId, RequestCommand command, String key) {
        return save(post, customerId, command, key, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public View convertLegacy(PostSnapshot post, long customerId, RequestCommand command, String key) {
        return save(post, customerId, command, key, true);
    }

    private View save(PostSnapshot post, long customerId, RequestCommand command, String key, boolean convert) {
        RequestCommand input = request(command);
        String action = convert ? "external-commission-convert" : "external-commission-register";
        var mutation = new Mutation(action, customerId, input);
        CustomerDO customer = lockCustomer(post, customerId, key);
        CommissionDO commission = lockCommission(post, customerId);
        if (convert && commission != null) lockPendingApprovals(post, commission.getId());
        if (replay(post, key, mutation, action, commission)) return get(post, customerId);
        revision(input.revision(), commission == null ? 0 : commission.getRevision());
        if (convert) {
            if (commission == null || external(commission) || !CONVERTIBLE.contains(commission.getStatus()))
                throw conflict("该委托当前不能转换，请先核对移交或工程接收状态");
            text(input.reason(), 500, true, "转换原因");
            requireHistoricalSettlement(post, commission);
        } else if (commission != null) {
            if (!external(commission)) throw conflict("已有旧委托，请使用旧流程转换入口");
            requireEditable(commission);
            text(input.reason(), 500, true, "变更原因");
        }
        var plan = plans.snapshotForEntrust(post, customerId);
        if (!PlanRef.of(plan).equals(input.paymentPlanRef())) throw exception(STATE_VERSION_CONFLICT);
        var saved = withMeasurement(post, customerId, commission, input);
        validateFiles(post, customerId, saved.attachments());
        String previousStatus = commission == null ? null : commission.getStatus();
        List<String> superseded = convert ? jdbc.queryForList("""
                UPDATE pf_approval SET status='SUPERSEDED'
                WHERE domain='franchise' AND org_id=? AND subject_type='commission-fee' AND subject_id=? AND status='PENDING'
                RETURNING id
                """, Long.class, post.orgId(), commission.getId()).stream().map(String::valueOf).toList() : List.of();
        if (commission == null) {
            commission = new CommissionDO().setCompanyId(post.orgId()).setDesignOrgId(post.orgId())
                    .setCustomerId(customerId).setCustomerName(customer.getName()).setSubmissionVersion(1)
                    .setWorkflowMode("EXTERNAL").setStatus(STATUS_EXTERNAL_PENDING).setRevision(0L);
            commissions.insert(commission);
        }
        int version = commission.getExternalRequestVersion() == null ? 1 : commission.getExternalRequestVersion() + 1;
        String kind = convert ? "CONVERT" : version == 1 ? "REGISTER" : "UPDATE";
        jdbc.update("""
                INSERT INTO fr_commission_external_version(commission_id,version,tenant_id,company_id,customer_id,
                    action,data,payment_plan,previous_status,superseded_approval_ids,actor_user_id,actor_post_id)
                    VALUES(?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?,CAST(? AS jsonb),?,?)
                """, commission.getId(), version, tenant(), post.orgId(), customerId, kind, JsonUtils.toJsonString(saved),
                plan == null ? null : JsonUtils.toJsonString(plan), previousStatus, JsonUtils.toJsonString(superseded), post.userId(), post.postId());
        long next = commission.getRevision() + 1;
        int updated = jdbc.update("""
                UPDATE fr_commission SET workflow_mode='EXTERNAL',status='EXTERNAL_PENDING',external_request_version=?,
                    external_result_version=NULL,revision=?,update_time=CURRENT_TIMESTAMP
                    WHERE id=? AND company_id=? AND revision=?
                """, version, next, commission.getId(), post.orgId(), commission.getRevision());
        if (updated != 1) throw exception(STATE_VERSION_CONFLICT);
        commands.record(post, AGGREGATE, commission.getId(), next, action, mutation, key);
        return get(post, customerId);
    }

    @Transactional(rollbackFor = Exception.class)
    public View results(PostSnapshot post, long customerId, ResultCommand command, String key) {
        ResultCommand input = result(command);
        String action = "external-commission-results";
        var mutation = new Mutation(action, customerId, input);
        lockCustomer(post, customerId, key);
        CommissionDO commission = lockCommission(post, customerId);
        if (replay(post, key, mutation, action, commission)) return get(post, customerId);
        if (commission == null || !external(commission)) throw conflict("请先登记外部设计委托");
        revision(input.revision(), commission.getRevision()); requireEditable(commission);
        if (!Objects.equals(commission.getExternalRequestVersion(), input.requestVersion())) throw exception(STATE_VERSION_CONFLICT);
        if (commission.getExternalResultVersion() != null) text(input.note(), 1000, true, "成果更正原因");
        List<Attachment> files = List.of(new Attachment(input.resultAssetId(), "外部设计成果"),
                new Attachment(input.drawingAssetId(), "外部施工图"), new Attachment(input.reviewAssetId(), "专业审核依据"));
        validateFiles(post, customerId, files);
        int version = jdbc.queryForObject("SELECT COALESCE(MAX(version),0)+1 FROM fr_commission_external_result WHERE commission_id=?", Integer.class, commission.getId());
        jdbc.update("""
                INSERT INTO fr_commission_external_result(commission_id,version,request_version,tenant_id,company_id,customer_id,
                    data,attachments,actor_user_id,actor_post_id) VALUES(?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?,?)
                """, commission.getId(), version, input.requestVersion(), tenant(), post.orgId(), customerId,
                JsonUtils.toJsonString(input), JsonUtils.toJsonString(files), post.userId(), post.postId());
        long next = commission.getRevision() + 1;
        int changed = jdbc.update("UPDATE fr_commission SET external_result_version=?,status='EXTERNAL_READY',revision=?,update_time=CURRENT_TIMESTAMP"
                + " WHERE id=? AND company_id=? AND revision=?", version, next, commission.getId(), post.orgId(), commission.getRevision());
        if (changed != 1) throw exception(STATE_VERSION_CONFLICT);
        commands.record(post, AGGREGATE, commission.getId(), next, action, mutation, key);
        return get(post, customerId);
    }

    @Transactional(rollbackFor = Exception.class)
    public AssetAccessPort.DownloadUrlView download(PostSnapshot post, long customerId, String kind, int version, long assetId) {
        requireCustomer(post, customerId, false);
        CommissionDO commission = commissions.selectByCustomer(customerId);
        if (commission == null || version < 1 || assetId <= 0 || !Set.of("requests", "results").contains(kind)) throw missing();
        String table = "requests".equals(kind) ? "fr_commission_external_version" : "fr_commission_external_result";
        String column = "requests".equals(kind) ? "data->'attachments'" : "attachments";
        String ref = JsonUtils.toJsonString(List.of(Map.of("assetId", Long.toString(assetId))));
        Boolean referenced = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + table
                + " WHERE tenant_id=? AND company_id=? AND customer_id=? AND commission_id=? AND version=? AND " + column + " @> CAST(? AS jsonb))",
                Boolean.class, tenant(), post.orgId(), customerId, commission.getId(), version, ref);
        if (!Boolean.TRUE.equals(referenced)) throw exception(RESOURCE_FORBIDDEN);
        validateFiles(post, customerId, List.of(new Attachment(Long.toString(assetId), "委托资料")));
        var ticket = assets.createDownloadUrl(actor(post), assetId);
        commands.record(post, "fr-external-document-read", assetId, com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),
                "external-commission-download", Map.of("commissionId", commission.getId(), "kind", kind, "version", version, "assetId", assetId),
                "external-download:" + UUID.randomUUID());
        return ticket;
    }

    private View view(PostSnapshot post, CustomerDO customer, CommissionDO commission, CustomerPaymentPlanDraftPort.Snapshot plan) {
        List<RequestVersion> requests = commission == null ? List.of() : requests(commission.getId(), post.orgId());
        List<ResultVersion> results = commission == null ? List.of() : results(commission.getId(), post.orgId());
        RequestVersion currentRequest = commission == null ? null : requests.stream()
                .filter(v -> Objects.equals(v.version(), commission.getExternalRequestVersion())).findFirst().orElse(null);
        ResultVersion currentResult = commission == null ? null : results.stream()
                .filter(v -> Objects.equals(v.version(), commission.getExternalResultVersion())).findFirst().orElse(null);
        boolean writer = CustomerAccess.canWrite(post, customer);
        boolean editable = commission == null || external(commission) && editable(commission.getStatus());
        boolean convertible = writer && commission != null && !external(commission) && CONVERTIBLE.contains(commission.getStatus())
                && (!simulations.blocked(commission) || simulations.reviewedForSettlement(commission)
                    && settlement.read(commission.getId(), commission.getSubmissionVersion()).readyToDispatch());
        var actions = new ArrayList<String>();
        if (writer && editable) {
            actions.add("external-entrust-register");
            if (currentRequest != null) actions.add("external-results-register");
            if (currentResult != null) actions.add("external-packet-submit");
        }
        if (convertible) actions.add("external-entrust-convert");
        return new View(commission == null ? null : commission.getId().toString(), customer.getId().toString(), customer.getName(),
                commission == null ? "EXTERNAL" : external(commission) ? "EXTERNAL" : "LEGACY", commission == null ? "NOT_REGISTERED" : commission.getStatus(),
                commission == null ? 0 : commission.getRevision(), writer && editable, convertible, List.copyOf(actions),
                currentRequest, currentResult, requests, results, plan, PlanRef.of(plan));
    }

    private RequestCommand withMeasurement(PostSnapshot post, long customerId, CommissionDO commission, RequestCommand input) {
        if (Boolean.TRUE.equals(input.removeMeasurement())) return input.withMeasurement(null, input.attachments());
        MeasurementRef ref = input.measurementRef();
        MeasurementRef frozen = null;
        if ((ref == null || !Boolean.TRUE.equals(ref.includeSourceNote())) && commission != null && commission.getExternalRequestVersion() != null) {
            String stored = jdbc.queryForObject("SELECT data->>'measurementRef' FROM fr_commission_external_version"
                            + " WHERE tenant_id=? AND company_id=? AND customer_id=? AND commission_id=? AND version=?",
                    String.class, tenant(), post.orgId(), customerId, commission.getId(), commission.getExternalRequestVersion());
            if (stored != null) frozen = JsonUtils.parseObject(stored, MeasurementRef.class);
        }
        if (ref == null) ref = frozen;
        if (ref == null) return input.withMeasurement(null, input.attachments());
        var source = measurements.require(tenant(), post.orgId(), customerId, ref);
        // Legacy editors either omit the source or echo its identity. Keep what the previous commission actually received.
        if (frozen != null && source.eventId().equals(frozen.eventId()) && source.revision().equals(frozen.revision())) {
            source = source.withNote(frozen.note());
        }
        var files = new LinkedHashMap<String,Attachment>();
        input.attachments().forEach(file -> files.put(file.assetId(), file));
        for (Attachment original : source.attachments()) {
            files.put(original.assetId(), new Attachment(original.assetId(), requestFileTitle(original.title())));
        }
        if (files.size() > 20) throw invalid("委托附件与测量原件合计最多20份");
        return input.withMeasurement(source, List.copyOf(files.values()));
    }

    // The immutable CRM evidence accepts 200 UTF-16 units or no title. Legacy commission forms re-submit a 1–180 unit title.
    private static String requestFileTitle(String original) {
        String name = original == null ? "" : original.replaceAll("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$", "");
        if (name.isBlank()) return "测量附件";
        if (name.length() <= 180) return name;
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 && Set.of("pdf","jpg","jpeg","png").contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))
                ? name.substring(dot) : "";
        int end = 180 - extension.length();
        if (Character.isHighSurrogate(name.charAt(end - 1))) end--;
        return name.substring(0, end) + extension;
    }

    List<RequestVersion> requests(long commission, long company) {
        return jdbc.query("SELECT * FROM fr_commission_external_version WHERE commission_id=? AND company_id=? AND tenant_id=? ORDER BY version DESC",
                (rs,n) -> new RequestVersion(rs.getInt("version"), rs.getString("action"), JsonUtils.parseObject(rs.getString("data"), RequestCommand.class),
                        rs.getString("payment_plan") == null ? null : JsonUtils.parseObject(rs.getString("payment_plan"), CustomerPaymentPlanDraftPort.Snapshot.class),
                        rs.getString("previous_status"), JsonUtils.parseArray(rs.getString("superseded_approval_ids"), String.class),
                        Long.toString(rs.getLong("actor_user_id")), rs.getObject("created_at", OffsetDateTime.class)), commission, company, tenant());
    }
    List<ResultVersion> results(long commission, long company) {
        return jdbc.query("SELECT * FROM fr_commission_external_result WHERE commission_id=? AND company_id=? AND tenant_id=? ORDER BY version DESC",
                (rs,n) -> new ResultVersion(rs.getInt("version"), JsonUtils.parseObject(rs.getString("data"), ResultCommand.class),
                        JsonUtils.parseObject(rs.getString("attachments"), FILES), Long.toString(rs.getLong("actor_user_id")),
                        rs.getObject("created_at", OffsetDateTime.class)), commission, company, tenant());
    }

    private CustomerDO lockCustomer(PostSnapshot post, long customerId, String key) {
        requireCustomer(post, customerId, true);
        String normalized = commands.requireIdempotencyKey(key);
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, FranchiseCommandKeys.lockKey(
                new FranchiseCommandKeys.PostKeyScope("franchise", post.userId()), normalized));
        // Also serialize against the retained legacy submit command; no second commission can win for this customer.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, "commission-customer:" + customerId);
        CustomerDO locked = customerMapper.lockById(customerId);
        if (locked == null || !CustomerAccess.canWrite(post, locked)) throw missing();
        return requireCustomer(post, customerId, true);
    }
    private CommissionDO lockCommission(PostSnapshot post, long customerId) {
        jdbc.queryForList("SELECT id FROM fr_commission WHERE customer_id=? AND company_id=? FOR UPDATE", Long.class, customerId, post.orgId());
        return commissions.selectByCustomer(customerId);
    }
    private void lockPendingApprovals(PostSnapshot post, long commissionId) {
        jdbc.queryForList("""
                SELECT id FROM pf_approval WHERE domain='franchise' AND org_id=? AND subject_type='commission-fee'
                  AND subject_id=? AND status='PENDING' ORDER BY id FOR UPDATE
                """, Long.class, post.orgId(), commissionId);
    }
    private CustomerDO requireCustomer(PostSnapshot post, long customerId, boolean write) {
        if (!post.equals(posts.require())) throw missing();
        Boolean valid = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM system_dept d JOIN pf_org_unit o ON o.dept_id=d.id
                WHERE d.id=? AND d.tenant_id=? AND d.status=0 AND d.deleted=0
                  AND o.kind='ORG' AND o.org_type='store' AND o.status='ACTIVE')
                """, Boolean.class, post.orgId(), tenant());
        if (!Boolean.TRUE.equals(valid)) throw missing();
        CustomerDO customer = customers.requireReadable(post, customerId);
        if (write && !CustomerAccess.canWrite(post, customer)) throw missing();
        return customer;
    }
    private boolean replay(PostSnapshot post, String key, Mutation input, String action, CommissionDO commission) {
        var replay = commands.replayed(post, key, input);
        if (replay.isEmpty()) return false;
        if (commission == null || !Objects.equals(replay.get().getAggregateId(), commission.getId()) || !action.equals(replay.get().getCommand()))
            throw exception(IDEMPOTENCY_KEY_REUSED);
        return true;
    }
    void requireHistoricalSettlement(PostSnapshot post, CommissionDO commission) {
        if (simulations.blocked(commission) && (!simulations.reviewedForSettlement(commission)
                || !settlement.lockAndRead(commission.getId(), commission.getSubmissionVersion(), post).readyToDispatch()))
            throw exception(HISTORICAL_SETTLEMENT_REQUIRED);
    }
    void validateFiles(PostSnapshot post, long customerId, List<Attachment> files) {
        if (files.isEmpty()) return;
        assetPolicy.requireAttachable(post, customerId, files);
        var query = evidence.getIfAvailable();
        if (query == null) throw new ServiceException(503, "资料验收服务暂不可用");
        Set<Long> ids = new LinkedHashSet<>(); files.forEach(f -> ids.add(assetId(f.assetId())));
        var owners = query.acceptedOwners(tenant(), ids, Set.of("BUSINESS_DOCUMENT"));
        for (long id : ids) {
            if (!Objects.equals(owners.get(id), post.orgId()) || !assets.validateReference(actor(post), id, Set.of("BUSINESS_DOCUMENT")))
                throw invalid("委托资料未验收或无权引用，请重新上传");
        }
    }
    private AssetAccessPort.Actor actor(PostSnapshot post) {
        return new AssetAccessPort.Actor(post.orgId(), accounts.requireServiceAccount(post.orgId()), post.userId(), post.postId());
    }
    static boolean external(CommissionDO commission) { return "EXTERNAL".equals(commission.getWorkflowMode()); }
    private static boolean editable(String status) { return Set.of(STATUS_EXTERNAL_PENDING, STATUS_EXTERNAL_READY, STATUS_HANDOVER_RETURNED).contains(status); }
    private static void requireEditable(CommissionDO commission) { if (!editable(commission.getStatus())) throw conflict("当前移交正在办理或已接收，不能改写委托资料"); }
    private static void revision(Long expected, long actual) { if (expected == null || expected < 0) throw invalid("委托版本无效"); if (expected != actual) throw exception(STATE_VERSION_CONFLICT); }
    static long tenant() { Long id=TenantContextHolder.getTenantId(); if(id==null) throw new ServiceException(401,"缺少租户身份"); return id; }
    private static RequestCommand request(RequestCommand value) {
        if (value == null || value.revision() == null || value.revision() < 0 || value.paymentPlanRef() == null) throw invalid("请填写委托并核对付款计划版本");
        date(value.entrustedOn(), "实际委托日期");
        if (value.area() != null && (value.area().signum() <= 0 || value.area().compareTo(new BigDecimal("999999.99")) > 0 || value.area().stripTrailingZeros().scale() > 2)) throw invalid("建筑面积须为有效的两位小数");
        if (value.floors() != null && (value.floors() < 1 || value.floors() > 20)) throw invalid("层数须在1至20之间");
        var files = value.attachments() == null ? List.<Attachment>of() : value.attachments();
        if (files.size() > 20) throw invalid("最多上传20份委托资料");
        var normalized = new ArrayList<Attachment>(); var ids = new HashSet<Long>();
        for (Attachment file : files) {
            if (file == null) throw invalid("委托资料无效"); long id = assetId(file.assetId());
            if (!ids.add(id)) throw invalid("同一附件不能重复登记");
            normalized.add(new Attachment(Long.toString(id), text(file.title(), 180, true, "附件名称")));
        }
        var source = ExternalCommissionMeasurements.selection(value.measurementRef());
        Boolean remove = Boolean.TRUE.equals(value.removeMeasurement()) ? true : null;
        if (source != null && remove != null) throw invalid("请选择测量版本或移除当前引用");
        return new RequestCommand(value.revision(), value.entrustedOn(), text(value.providerName(),100,true,"外部服务方"),
                text(value.providerReference(),100,false,"外部编号"), text(value.text(),2000,true,"需求说明"), text(value.scope(),500,true,"服务范围"),
                value.area(), value.floors(), List.copyOf(normalized), value.paymentPlanRef(), text(value.reason(),500,false,"变更原因"), source, remove);
    }
    private static ResultCommand result(ResultCommand value) {
        if (value == null || value.revision() == null || value.revision() < 0 || value.requestVersion() < 1) throw invalid("成果对应的委托版本无效");
        date(value.deliveredOn(), "实际成果交付日期");
        return new ResultCommand(value.revision(), value.requestVersion(), value.deliveredOn(), text(value.drawingVersion(),64,true,"图纸版本"),
                Long.toString(assetId(value.resultAssetId())), Long.toString(assetId(value.drawingAssetId())), Long.toString(assetId(value.reviewAssetId())), text(value.note(),1000,false,"成果说明"));
    }
    private static long assetId(String raw) {
        try { if (raw == null || !raw.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException(); return Long.parseLong(raw); }
        catch (NumberFormatException error) { throw invalid("附件编号无效"); }
    }
    private static String text(String value, int max, boolean required, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > max || required && normalized.isBlank()) throw invalid(label + "必填且不能超过" + max + "字");
        return normalized;
    }
    private static void date(LocalDate value, String label) { if (value == null || value.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))) throw invalid(label + "须为已发生的日期"); }
    private static ServiceException invalid(String message) { return new ServiceException(400, message); }
    private static ServiceException conflict(String message) { return new ServiceException(409, message); }
    private static ServiceException missing() { return new ServiceException(404, "委托不存在或无权查看"); }
}
