package cn.iocoder.yudao.module.franchise.commission;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.franchise.api.DesignFeeProgressApi;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionDO;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionMapper;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionProgressDO;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionProgressMapper;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionSubmissionDO;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionSubmissionMapper;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionHandoverDO;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionHandoverMapper;
import cn.iocoder.yudao.module.franchise.crm.CustomerAccess;
import cn.iocoder.yudao.module.franchise.crm.CustomerService;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerMapper;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerSchemeDO;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.platform.command.Revisions;
import cn.iocoder.yudao.module.platform.org.OrgService;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.workflow.ApprovalService;
import cn.iocoder.yudao.module.platform.workflow.dal.ApprovalDO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.commission.enums.FranchiseCommissionErrorCodes.*;
import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.*;

/**
 * 设计委托状态机（sales-offline-v1，M2 设计 §7）：提交/费用审批/设计完成/成果下发/修改意见，
 * 以及首尾款进度写入。每个写命令推进委托 revision 并记一条命令事件。
 */
@Service
public class CommissionService implements DesignFeeProgressApi {

    public static final String AGGREGATE = "commission";
    public static final String SUBJECT_TYPE = "commission-fee";

    public record SubmitCommand(String text, String scope, String attachment, BigDecimal total, BigDecimal first,
                                String recipient, Integer budgetVersion, Integer planVersion, Integer facadeVersion,
                                boolean confirmed, String mode, Integer feeRuleVersion, Long attachmentAssetId, java.util.Map<String,Object> designBrief, String paymentMode) {
        public SubmitCommand(String text,String scope,String attachment,BigDecimal total,BigDecimal first,String recipient,Integer budgetVersion,Integer planVersion,Integer facadeVersion,boolean confirmed,String mode,Integer feeRuleVersion,Long attachmentAssetId,java.util.Map<String,Object> designBrief){this(text,scope,attachment,total,first,recipient,budgetVersion,planVersion,facadeVersion,confirmed,mode,feeRuleVersion,attachmentAssetId,designBrief,null);}
        public SubmitCommand(String text,String scope,String attachment,BigDecimal total,BigDecimal first,String recipient,Integer budgetVersion,Integer planVersion,Integer facadeVersion,boolean confirmed,String mode,Integer feeRuleVersion,Long attachmentAssetId){this(text,scope,attachment,total,first,recipient,budgetVersion,planVersion,facadeVersion,confirmed,mode,feeRuleVersion,attachmentAssetId,null);}
        public SubmitCommand(String text,String scope,String attachment,BigDecimal total,BigDecimal first,String recipient,
                             Integer budgetVersion,Integer planVersion,Integer facadeVersion,boolean confirmed) {
            this(text,scope,attachment,total,first,recipient,budgetVersion,planVersion,facadeVersion,confirmed,"LEGACY_SPLIT",null,null);
        }
    }

    public record FeeDecisionCommand(boolean approve, String note) {
    }

    public record CompleteCommand(String note, boolean confirmed) {
    }

    public record DispatchCommand(String note, String recipient, boolean confirmed, String drawingVersion,
                                  Long resultAssetId, Long drawingAssetId, Long reviewAssetId) {
        public DispatchCommand(String note,String recipient,boolean confirmed) { this(note,recipient,confirmed,null,null,null,null); }
    }

    public record FeedbackCommand(String note) {
    }

    /** 委托详情：按岗位裁剪金额后展示 */
    public record Detail(CommissionDO commission, List<CommissionSubmissionDO> submissions,
                         List<CommissionProgressDO> progress, List<CommissionHandoverDO> handovers,
                         ApprovalDO feeApproval, List<String> allowedActions, String displayStatus,
                         cn.iocoder.yudao.module.franchise.api.DesignFeeSettlementPort.Settlement settlement,
                         List<CommissionManifestService.Manifest> dispatches, CommissionSimulatedPayments.Order simulatedPayment) {
    }

    private final CommissionMapper commissionMapper;
    private final CommissionSubmissionMapper submissionMapper;
    private final CommissionProgressMapper progressMapper;
    private final CommissionHandoverMapper handoverMapper;
    private final cn.iocoder.yudao.module.franchise.commission.dal.DesignPolicyMapper policyMapper;
    private final CustomerMapper customerMapper;
    private final CustomerService customerService;
    private final OrgService orgService;
    private final ApprovalService approvalService;
    private final FranchiseCommands commands;
    private final CommissionAccess access;
    @jakarta.annotation.Resource private cn.iocoder.yudao.module.franchise.api.DesignFeeSettlementPort settlement;
    @jakarta.annotation.Resource private DesignPricingService pricing;
    @jakarta.annotation.Resource private CommissionSimulatedPayments simulatedPayments;
    @jakarta.annotation.Resource private CommissionManifestService manifests;
    @jakarta.annotation.Resource private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ExternalHandoverAccess externalHandovers;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.iocoder.yudao.module.franchise.api.EngineeringProjectContextPort engineeringContexts;

    private boolean simulationAllowed(CommissionDO commission) {
        return simulatedPayments.allowed(commission.getCompanyId(), commission.getDesignOrgId());
    }

    private static final String LEGACY_SIMULATION = "SIMULATION";
    private static final String TEST_SIMULATION = "TEST_SIMULATION";

    public record FeeOffer(String mode,Integer ruleVersion,BigDecimal area,Long rateCents,long totalCents,
                           long firstCents,long tailCents,String scope,String recipient,String simulationPaymentMode) { }
    public record Context(long customerId,Long commissionId,CustomerSchemeDO latestBudget,FeeOffer feeOffer,
                          Detail commission,List<String> allowedActions,String customerName,String city,boolean canPrepareBudget,java.util.Map<String,Object> customerInfo,java.util.Map<String,String> schemeBasis) { }

    public Context context(PostSnapshot post,long customerId) {
        CustomerDO customer=customerMapper.selectById(customerId);
        if(customer==null || !CustomerAccess.canRead(post,customer)) throw exception(COMMISSION_NOT_FOUND);
        var c=commissionMapper.selectByCustomer(customerId);
        if(c!=null && ExternalCommissionService.external(c)) return new Context(customerId,c.getId(),null,null,detail(post,c),
                allowedActions(post,c),customer.getName(),customer.getCity(),false,CommissionBrief.customerInfo(customer),java.util.Map.of());
        var budget=customerService.latestScheme(customerId,CustomerSchemeDO.KIND_BUDGET);
        FeeOffer offer=null;
        long designOrgId = soleHeadquarters();
        var policy=pricing.current(designOrgId);
        if(policy!=null && budget!=null && budget.getArea()!=null) offer=new FeeOffer("AREA_FULL",policy.version(),budget.getArea(),policy.rateCents(),
                DesignPricingService.totalCents(budget.getArea(),policy.rateCents()),0,0,policy.scope(),policy.recipient(),simulatedPayments.allowed(customer.getCompanyId(),designOrgId)?TEST_SIMULATION:null);
        else {
            var legacy=policyOf(post.orgId());
            if(legacy!=null) offer=new FeeOffer("LEGACY_SPLIT",legacy.getVersion(),budget==null?null:budget.getArea(),null,
                    legacy.getTotalCents(),legacy.getFirstCents(),legacy.getTotalCents()-legacy.getFirstCents(),legacy.getScope(),legacy.getRecipient(),null);
        }
        return new Context(customerId,c==null?null:c.getId(),budget,offer,c==null?null:detail(post,c),c==null?List.of():allowedActions(post,c),customer.getName(),customer.getCity(),CustomerAccess.canWrite(post,customer),CommissionBrief.customerInfo(customer),schemeBasis(customerId,budget));
    }
    private java.util.Map<String,String> schemeBasis(long customerId,CustomerSchemeDO budget) {
        var result=new java.util.LinkedHashMap<String,String>();
        if(budget==null)return result;
        for(String kind:List.of(CustomerSchemeDO.KIND_PLAN,CustomerSchemeDO.KIND_FACADE)){
            var s=matchingScheme(customerId,budget,kind);
            if(s==null||s.getPayload()==null)continue;
            result.put(kind,s.getPayload());
        }
        return result;
    }
    private CustomerSchemeDO matchingScheme(long customerId,CustomerSchemeDO budget,String kind){
        var s=customerService.latestScheme(customerId,kind);if(s==null||budget==null)return null;
        boolean same=budget.getDesignProjectId()!=null&&java.util.Objects.equals(budget.getDesignProjectId(),s.getDesignProjectId());
        if(!same&&budget.getDesignProjectId()==null&&s.getWidth()!=null&&s.getDepth()!=null&&s.getFloors()!=null)
            same=budget.getWidth()!=null&&budget.getDepth()!=null&&s.getWidth().compareTo(budget.getWidth())==0&&s.getDepth().compareTo(budget.getDepth())==0&&s.getFloors().equals(budget.getFloors());
        return same?s:null;
    }
    public CommissionSimulatedPayments.Order simulatedPayment(CommissionDO c){
        return simulationAllowed(c) ? simulatedPayments.read(c) : null;
    }

    void requireHistoricalSettlement(PostSnapshot actor, CommissionDO commission) {
        if (simulatedPayments.blocked(commission) && (!simulatedPayments.reviewedForSettlement(commission)
                || !settlement.lockAndRead(commission.getId(),commission.getSubmissionVersion(),actor).readyToDispatch())) {
            throw exception(HISTORICAL_SETTLEMENT_REQUIRED);
        }
    }

    private boolean pendingHistoricalReview(CommissionDO commission) {
        return simulatedPayments.blocked(commission) && !simulatedPayments.reviewedForSettlement(commission);
    }

    public boolean readyToComplete(CommissionDO c) {
        var order = simulatedPayment(c);
        return order == null ? !pendingHistoricalReview(c) && settlement(c).readyToComplete() : order.paid();
    }

    public boolean readyToDispatch(CommissionDO c) {
        var order = simulatedPayment(c);
        return order == null ? !pendingHistoricalReview(c) && settlement(c).readyToDispatch() : order.paid();
    }

    public record HistoricalSettlementCommand(int submissionVersion, String reviewNote) { }

    /** Start the normal finance workflow for one reviewed historical order. */
    @Transactional(rollbackFor=Exception.class)
    public void prepareHistoricalSettlement(PostSnapshot actor, long commissionId,
                                           HistoricalSettlementCommand command, String key) {
        var commission = commissionMapper.selectById(commissionId);
        if (commission == null || commission.getCompanyId() != actor.orgId()
                || !FranchiseRoles.atStore(actor) || !FranchiseRoles.FINANCE_LEAD.equals(actor.roleCode())) {
            throw exception(COMMISSION_FORBIDDEN);
        }
        String note = command == null ? null : StrUtil.trimToNull(command.reviewNote());
        if (command == null || command.submissionVersion() != commission.getSubmissionVersion()
                || note == null || note.length() > 500 || !simulatedPayments.blocked(commission)) {
            throw exception(COMMISSION_STATE_INVALID);
        }

        key = commandLock(actor, key);
        var input = new HistoricalSettlementCommand(command.submissionVersion(), note);
        String action = CommissionSimulatedPayments.HISTORICAL_REVIEW_COMMAND;
        if (replayed(actor, key, input, commissionId, action)) return;
        settlement.ensureInitialPayable(commissionId, command.submissionVersion(), actor);
        // The payable's stage lock also serializes the review event for this version.
        if (!simulatedPayments.reviewedForSettlement(commission)) {
            commands.record(actor,CommissionSimulatedPayments.HISTORICAL_REVIEW_AGGREGATE,commissionId,
                    command.submissionVersion(),action,input,key);
        }
    }
    public record SimulatePayCommand(String channel,boolean confirmed) { }
    @Transactional(rollbackFor=Exception.class)
    public Detail simulatePay(PostSnapshot post,long commissionId,SimulatePayCommand input,String key){
        requireWritable(post,commissionId);
        key=commandLock(post,key);
        if(replayed(post,key,input,commissionId,"commission-simulate-pay"))return get(post,commissionId);
        throw exception(SIMULATION_FORBIDDEN);
    }
    public CommissionSimulatedPayments.Order paymentReceipt(PostSnapshot post,long commissionId){
        var c=commissionMapper.selectById(commissionId);
        boolean finance=c!=null&&Set.of(FranchiseRoles.FINANCE,FranchiseRoles.FINANCE_LEAD).contains(post.roleCode())&&(post.orgId()==c.getCompanyId()||post.orgId()==c.getDesignOrgId());
        if(c==null||!finance&&!access.canRead(post,c))throw exception(COMMISSION_NOT_FOUND);
        var order=simulatedPayments.read(c);
        if(order==null)throw exception(COMMISSION_NOT_FOUND);
        boolean reviewed = simulatedPayments.reviewedForSettlement(c);
        boolean canReview = !reviewed && FranchiseRoles.atStore(post) && post.orgId() == c.getCompanyId()
                && FranchiseRoles.FINANCE_LEAD.equals(post.roleCode());
        return access.isDesignAdminOf(post,c)?order.designView():order.withSettlementReview(reviewed,canReview);
    }
    /** Existing financial review work, only exposed to the responsible store finance lead. */
    public List<CommissionDO> pendingHistoricalSettlements(PostSnapshot post) {
        if (!FranchiseRoles.atStore(post) || !FranchiseRoles.FINANCE_LEAD.equals(post.roleCode())) return List.of();
        var ids = jdbc.queryForList("""
                SELECT c.id FROM fr_commission c
                WHERE c.company_id=? AND EXISTS(SELECT 1 FROM fr_commission_simulated_order s
                    WHERE s.commission_id=c.id AND s.submission_version=c.submission_version)
                  AND NOT EXISTS(SELECT 1 FROM pf_command_event e WHERE e.domain='franchise'
                    AND e.org_id=c.company_id AND e.aggregate_type='commission-historical-settlement'
                    AND e.aggregate_id=c.id AND e.revision=c.submission_version
                    AND e.command='commission-historical-settlement-prepare')
                ORDER BY c.update_time,c.id
                """, Long.class,post.orgId());
        return ids.isEmpty()?List.of():commissionMapper.selectByIds(ids);
    }

    public cn.iocoder.yudao.module.franchise.api.DesignFeeSettlementPort.Settlement settlement(CommissionDO c) {
        return settlement.read(c.getId(),c.getSubmissionVersion());
    }
    public List<CommissionManifestService.Manifest> dispatches(long commissionId) { return manifests.list(commissionId); }
    private String commandLock(PostSnapshot post,String key) {
        key=commands.requireIdempotencyKey(key);
        long lock=cn.iocoder.yudao.module.franchise.org.FranchiseCommandKeys.lockKey(
                new cn.iocoder.yudao.module.franchise.org.FranchiseCommandKeys.PostKeyScope("franchise",post.userId()),key);
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(?)) s",Integer.class,lock);
        return key;
    }
    private boolean replayed(PostSnapshot post,String key,Object input,long id,String action) {
        var replay=commands.replayed(post,key,input);
        if(replay.isEmpty()) return false;
        var event=replay.get();
        if(event.getAggregateId()!=id || !action.equals(event.getCommand())) throw exception(
                cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED);
        return true;
    }

    public CommissionService(CommissionMapper commissionMapper, CommissionSubmissionMapper submissionMapper,
                             CommissionProgressMapper progressMapper, CommissionHandoverMapper handoverMapper,
                             cn.iocoder.yudao.module.franchise.commission.dal.DesignPolicyMapper policyMapper,
                             CustomerMapper customerMapper, CustomerService customerService, OrgService orgService,
                             ApprovalService approvalService,
                             FranchiseCommands commands, CommissionAccess access) {
        this.commissionMapper = commissionMapper;
        this.submissionMapper = submissionMapper;
        this.progressMapper = progressMapper;
        this.handoverMapper = handoverMapper;
        this.policyMapper = policyMapper;
        this.customerMapper = customerMapper;
        this.customerService = customerService;
        this.orgService = orgService;
        this.approvalService = approvalService;
        this.commands = commands;
        this.access = access;
    }

    // ===================== 查询 =====================

    public Detail get(PostSnapshot post, long commissionId) {
        CommissionDO commission = requireReadable(post, commissionId);
        return detail(post, commission);
    }

    public PageResult<CommissionDO> page(PostSnapshot post, PageParam page, String keyword) {
        return page(post, page, keyword, null);
    }

    public PageResult<CommissionDO> page(PostSnapshot post, PageParam page, String keyword, String statuses) {
        var filters = statuses == null || statuses.isBlank() ? List.<String>of()
                : java.util.Arrays.stream(statuses.split(",")).map(String::trim).distinct().toList();
        if (!java.util.Set.of("FEE_PENDING", "FEE_RETURNED", "DESIGNING", "DESIGN_COMPLETED", "DISPATCHED",
                "HANDOVER_SUBMITTED", "HANDOVER_RETURNED", "ACCEPTED", "MODIFYING", "EXTERNAL_PENDING", "EXTERNAL_READY").containsAll(filters)) {
            throw exception(cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.BAD_REQUEST);
        }
        return commissionMapper.selectPage(page, access.visibleTo(post,
                visibleCustomerIds(post), managedCommissionIds(post)), keyword, filters);
    }

    /** 总部设计台全量状态计数，不依赖客户端已经加载了多少页。 */
    public java.util.Map<String,Long> workbenchCounts(PostSnapshot post) {
        if (!FranchiseRoles.atHeadquarters(post) || !FranchiseRoles.DESIGN_ADMIN.equals(post.roleCode())) {
            throw exception(COMMISSION_FORBIDDEN);
        }
        var counts = new java.util.LinkedHashMap<String,Long>();
        jdbc.query("SELECT status, COUNT(*) AS total FROM fr_commission WHERE design_org_id=? GROUP BY status",
                rs -> { counts.put(rs.getString("status"), rs.getLong("total")); }, post.orgId());
        return counts;
    }

    public record RecentHandling(String commissionId, String customerName, String kind, String handledAt) { }

    /** 每个可见委托的最后一次设计办理，按事件时间和 ID 稳定取最近五项。 */
    public List<RecentHandling> workbenchRecent(PostSnapshot post) {
        if (!FranchiseRoles.atHeadquarters(post) || !FranchiseRoles.DESIGN_ADMIN.equals(post.roleCode())) {
            throw exception(COMMISSION_FORBIDDEN);
        }
        return jdbc.query("""
                SELECT commission_id, customer_name, kind, create_time FROM (
                    SELECT DISTINCT ON (c.id) c.id AS commission_id, c.customer_name,
                           p.id AS event_id, p.kind, p.create_time
                    FROM fr_commission c JOIN fr_commission_progress p
                      ON p.commission_id=c.id AND p.company_id=c.company_id AND p.design_org_id=c.design_org_id
                    WHERE c.design_org_id=? AND p.kind IN ('COMPLETE','DISPATCH')
                    ORDER BY c.id, p.create_time DESC, p.id DESC
                ) recent ORDER BY create_time DESC, event_id DESC LIMIT 5
                """, (rs, row) -> new RecentHandling(Long.toString(rs.getLong("commission_id")),
                rs.getString("customer_name"), rs.getString("kind"),
                rs.getObject("create_time", OffsetDateTime.class).toString()), post.orgId());
    }

    /** 当前岗位可见的全部委托（待办即时计算用） */
    public java.util.List<CommissionDO> listVisible(PostSnapshot post) {
        return commissionMapper.selectVisible(access.visibleTo(post,
                visibleCustomerIds(post), managedCommissionIds(post)));
    }

    public CommissionDO requireReadable(PostSnapshot post, long commissionId) {
        CommissionDO commission = commissionMapper.selectById(commissionId);
        if (commission!=null && ExternalCommissionService.external(commission)) {
            if (externalHandovers!=null) return externalHandovers.readable(post,commissionId).commission();
            var customer=customerMapper.selectById(commission.getCustomerId());
            if (customer==null || !CustomerAccess.canRead(post,customer)) throw exception(COMMISSION_NOT_FOUND);
            return commission;
        }
        boolean manager = commission != null && handoverMapper.selectByCommission(commissionId).stream()
                .anyMatch(h -> access.isHandoverManager(post, commission.getCompanyId(), h.getManagerUserId()));
        if (commission == null || !manager && !access.canRead(post, commission)) {
            throw exception(COMMISSION_NOT_FOUND);
        }
        return commission;
    }

    /** 可写校验：不可见报「不存在」，可见不可办报「不能办理」 */
    public CommissionDO requireWritable(PostSnapshot post, long commissionId) {
        CommissionDO commission = commissionMapper.selectById(commissionId);
        if (commission == null || !access.canRead(post, commission)) {
            throw exception(COMMISSION_NOT_FOUND);
        }
        if (!access.canWrite(post, commission)) {
            throw exception(COMMISSION_FORBIDDEN);
        }
        return commission;
    }

    private Detail detail(PostSnapshot post, CommissionDO commission) {
        if (ExternalCommissionService.external(commission) && externalHandovers != null) {
            var subject = externalHandovers.readable(post, commission.getId());
            if (!CustomerAccess.canRead(post, subject.customer())) {
                // Engineering access is to the current frozen packet, not the customer's legacy business history.
                var current = subject.commission();
                var visible = new CommissionDO().setId(current.getId()).setCompanyId(current.getCompanyId())
                        .setCustomerId(current.getCustomerId()).setCustomerName(subject.customer().getName())
                        .setStatus(current.getStatus()).setWorkflowMode(current.getWorkflowMode())
                        .setExternalRequestVersion(current.getExternalRequestVersion())
                        .setExternalResultVersion(current.getExternalResultVersion())
                        .setHandoverVersion(current.getHandoverVersion()).setRevision(current.getRevision());
                var packets = externalHandovers.packets(current.getId(), current.getCompanyId()).stream()
                        .filter(packet -> externalHandovers.canReadPacket(post, subject, packet)).toList();
                return new Detail(visible, List.of(), List.of(), packets, null, List.of(),
                        displayStatus(visible), null, List.of(), null);
            }
        }
        boolean designAdmin = access.isDesignAdminOf(post, commission);
        List<CommissionSubmissionDO> submissions = submissionMapper.selectByCommission(commission.getId());
        if (designAdmin) {
            // 字段裁剪（§6）：设计行政看不到金额与收款方
            submissions = submissions.stream().map(s -> cn.iocoder.yudao.framework.common.util.object.BeanUtils.toBean(s,CommissionSubmissionDO.class))
                    .map(s -> s.setTotalCents(null).setFirstCents(null)
                    .setRecipient(null).setPolicyId(null).setPolicyVersion(null)
                    .setRateCents(null).setPricingVersion(null).setSchemeSnapshot(CommissionBrief.forDesign(s.getSchemeSnapshot()))).toList();
        }
        ApprovalDO approval = approvalService.latest(FranchiseRoles.DOMAIN, SUBJECT_TYPE, commission.getId()).orElse(null);
        if(designAdmin && approval!=null) {
            approval=cn.iocoder.yudao.framework.common.util.object.BeanUtils.toBean(approval,ApprovalDO.class).setAmountCents(null);
        }
        var simulated = simulatedPayment(commission);
        var packets=handoverMapper.selectByCommission(commission.getId());
        return new Detail(commission, submissions, progressMapper.selectByCommission(commission.getId()),
                packets, approval, allowedActions(post, commission),
                displayStatus(commission), designAdmin || simulated != null || ExternalCommissionService.external(commission) ? null : settlement(commission),
                manifests.list(commission.getId()), designAdmin && simulated != null ? simulated.designView() : simulated);
    }

    // ===================== 命令 =====================

    /** Retired entry: only replay a matching previously committed submission. */
    @Transactional(rollbackFor = Exception.class)
    public Detail submit(PostSnapshot post, long customerId, SubmitCommand command, String idempotencyKey) {
        customerService.requireWritable(post, customerId);
        idempotencyKey=commandLock(post,idempotencyKey);
        SubmitCommand input = normalize(command);
        var replay=commands.replayed(post,idempotencyKey,input);
        if(replay.isPresent()) {
            var previous=commissionMapper.selectById(replay.get().getAggregateId());
            if(previous==null || previous.getCustomerId()!=customerId || !AGGREGATE.equals(replay.get().getAggregateType()) || !"commission-submit".equals(replay.get().getCommand()))
                throw exception(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED);
            return get(post,previous.getId());
        }
        // Old successful commands remain replayable; no new internal design version or financial source is created.
        throw exception(LEGACY_COMMISSION_RETIRED);
    }

    /** Historical fee decision receipts remain readable; no new internal fee approval is issued. */
    @Transactional(rollbackFor = Exception.class)
    public Detail feeDecide(PostSnapshot post, long commissionId, FeeDecisionCommand command, String idempotencyKey) {
        idempotencyKey=commandLock(post,idempotencyKey);
        if(replayed(post,idempotencyKey,command,commissionId,"commission-fee-decide")) return get(post,commissionId);
        var commission = commissionMapper.selectById(commissionId);
        if (commission == null || commission.getCompanyId() != post.orgId()
                || !FranchiseRoles.atStore(post) || !FranchiseRoles.isBoss(post)) throw exception(FEE_DECISION_FORBIDDEN);
        throw exception(LEGACY_COMMISSION_RETIRED);
    }

    /** Retired design completion: preserve committed command receipts only. */
    @Transactional(rollbackFor = Exception.class)
    public Detail complete(PostSnapshot post, long commissionId, CompleteCommand command, String idempotencyKey) {
        idempotencyKey=commandLock(post,idempotencyKey);
        if(replayed(post,idempotencyKey,command,commissionId,"design-complete")) return get(post,commissionId);
        CommissionDO commission = commissionMapper.selectById(commissionId);
        if (commission == null || !access.isDesignAdminOf(post, commission)) {
            throw exception(COMMISSION_FORBIDDEN);
        }
        throw exception(LEGACY_COMMISSION_RETIRED);
    }

    /** Retired internal dispatch: immutable historical deliveries remain available. */
    @Transactional(rollbackFor = Exception.class)
    public Detail dispatch(PostSnapshot post, long commissionId, DispatchCommand command, String idempotencyKey) {
        idempotencyKey=commandLock(post,idempotencyKey);
        if(replayed(post,idempotencyKey,command,commissionId,"design-dispatch")) return get(post,commissionId);
        CommissionDO commission = commissionMapper.selectById(commissionId);
        if (commission == null || !access.isDesignAdminOf(post, commission)) {
            throw exception(COMMISSION_FORBIDDEN);
        }
        throw exception(LEGACY_COMMISSION_RETIRED);
    }

    /** Retired internal feedback: existing dispatch and financial facts are never cleared. */
    @Transactional(rollbackFor = Exception.class)
    public Detail feedback(PostSnapshot post, long commissionId, FeedbackCommand command, String idempotencyKey) {
        idempotencyKey=commandLock(post,idempotencyKey);
        if(replayed(post,idempotencyKey,command,commissionId,"commission-feedback")) return get(post,commissionId);
        requireWritable(post, commissionId);
        throw exception(LEGACY_COMMISSION_RETIRED);
    }

    // ===================== 首尾款进度（franchise-api，M3 财务调用） =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markFirstReady(long commissionId, int submissionVersion, PostSnapshot actor) {
        markProgress(commissionId, submissionVersion, actor, CommissionDO::getFirstReadyAt,
                u -> u.set(CommissionDO::getFirstReadyAt, OffsetDateTime.now()), "fee-first-ready");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markTailPaid(long commissionId, int submissionVersion, PostSnapshot actor) {
        markProgress(commissionId, submissionVersion, actor, CommissionDO::getTailPaidAt,
                u -> u.set(CommissionDO::getTailPaidAt, OffsetDateTime.now()), "fee-tail-paid");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markTailReady(long commissionId, int submissionVersion, PostSnapshot actor) {
        markProgress(commissionId, submissionVersion, actor, CommissionDO::getTailReadyAt,
                u -> u.set(CommissionDO::getTailReadyAt, OffsetDateTime.now()), "fee-tail-ready");
    }

    private void markProgress(long commissionId, int submissionVersion, PostSnapshot actor,
                              java.util.function.Function<CommissionDO, OffsetDateTime> current,
                              java.util.function.Consumer<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CommissionDO>> column,
                              String command) {
        CommissionDO commission = commissionMapper.selectById(commissionId);
        if (commission == null) {
            throw exception(COMMISSION_NOT_FOUND);
        }
        if (commission.getSubmissionVersion() != submissionVersion) {
            throw exception(COMMISSION_STATE_INVALID);
        }
        if (current.apply(commission) != null) {
            return; // 已写入的进度不清除、不重复记事件
        }
        long revision = commission.getRevision();
        Revisions.requireUpdated(commissionMapper.updateByRevision(commissionId, revision, column));
        commands.record(actor, AGGREGATE, commissionId, revision + 1, command, Map.of("submissionVersion", submissionVersion), null);
    }

    // ===================== 内部工具 =====================

    private cn.iocoder.yudao.module.franchise.commission.dal.DesignPolicyDO policyOf(long orgId) {
        return policyMapper.selectActive(orgId);
    }

    private long soleHeadquarters() {
        List<Long> ids = orgService.listActiveOrgIds(FranchiseRoles.ORG_HEADQUARTERS);
        if (ids.size() != 1) {
            throw exception(DESIGN_ORG_REQUIRED);
        }
        return ids.get(0);
    }

    private static SubmitCommand normalize(SubmitCommand c) {
        String text = StrUtil.trim(c.text());
        String scope = StrUtil.trim(c.scope());
        String recipient = StrUtil.trim(c.recipient());
        String attachment = StrUtil.trimToNull(c.attachment());
        String mode=c.mode()==null?"LEGACY_SPLIT":c.mode();
        if(!"AREA_FULL".equals(mode) && !"LEGACY_SPLIT".equals(mode)) throw exception(SUBMISSION_INPUT_INVALID);
        if("LEGACY_SPLIT".equals(mode)) DesignPolicyService.requireAmount(c.total(), c.first());
        if (!c.confirmed() || StrUtil.isBlank(text) || text.length() > 2000
                || StrUtil.isBlank(scope) || scope.length() > 500
                || "LEGACY_SPLIT".equals(mode) && (StrUtil.isBlank(recipient) || recipient.length() > 64)
                || attachment != null) {
            throw exception(SUBMISSION_INPUT_INVALID);
        }
        if (c.paymentMode()!=null && (!"AREA_FULL".equals(mode)
                || !Set.of(LEGACY_SIMULATION,TEST_SIMULATION).contains(c.paymentMode()))) {
            throw exception(SUBMISSION_INPUT_INVALID);
        }
        return new SubmitCommand(text, scope, attachment, c.total(), c.first(), recipient,
                c.budgetVersion(), c.planVersion(), c.facadeVersion(), true,mode,c.feeRuleVersion(),c.attachmentAssetId(),CommissionBrief.validate(c.designBrief()),c.paymentMode());
    }

    /** 可见客户集合：本岗位可读的客户的编号（列表过滤用） */
    private java.util.Set<Long> visibleCustomerIds(PostSnapshot post) {
        return java.util.Set.copyOf(customerMapper.selectVisible(CustomerAccess.visibleTo(post)).stream()
                .map(CustomerDO::getId).toList());
    }

    /** 本人是接收人的委托集合 */
    private java.util.Set<Long> managedCommissionIds(PostSnapshot post) {
        if (!FranchiseRoles.has(post,FranchiseRoles.ENGINEER)) return java.util.Set.of();
        var packets=handoverMapper.selectList(
                        new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<CommissionHandoverDO>()
                                .eq(CommissionHandoverDO::getManagerUserId, post.userId()));
        var managed=new java.util.HashSet<Long>();
        var ids=packets.stream().map(CommissionHandoverDO::getCommissionId).collect(java.util.stream.Collectors.toSet());
        if (!ids.isEmpty()) for (var row:commissionMapper.selectList(
                new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<CommissionDO>().in(CommissionDO::getId,ids))) {
            if (!ExternalCommissionService.external(row) || packets.stream().anyMatch(h -> h.getCommissionId().equals(row.getId())
                    && java.util.Objects.equals(h.getVersion(),row.getHandoverVersion()) && !"ACCEPTED".equals(h.getStatus()))) managed.add(row.getId());
        }
        if (engineeringContexts!=null) managed.addAll(engineeringContexts.sourceCommissionsForActor(post.orgId(),post.userId()));
        return java.util.Set.copyOf(managed);
    }

    // ===================== 展示 =====================

    /** 显示状态：存储状态 + 首尾款进度（文案与小程序 sales-flow.js next() 一致） */
    public String displayStatus(CommissionDO commission) {
        if (ExternalCommissionService.external(commission)) return switch (commission.getStatus()) {
            case CommissionDO.STATUS_EXTERNAL_PENDING -> "外部设计中 · 待登记成果";
            case CommissionDO.STATUS_EXTERNAL_READY -> "外部成果已登记 · 待工程移交";
            case CommissionDO.STATUS_HANDOVER_SUBMITTED -> "移交待工程接收";
            case CommissionDO.STATUS_HANDOVER_RETURNED -> "移交被退回 · 待补充资料";
            case CommissionDO.STATUS_ACCEPTED -> "工程已接收";
            default -> commission.getStatus();
        };
        if (simulatedPayments.blocked(commission) && !readyToDispatch(commission)) {
            return "历史模拟委托 · 待正式结算";
        }
        var simulated=simulatedPayment(commission);
        if(simulated!=null&&!simulated.paid())return "历史模拟记录待核对";
        return switch (commission.getStatus()) {
            case CommissionDO.STATUS_FEE_PENDING -> "历史费用待核对 · 待转外部流程";
            case CommissionDO.STATUS_FEE_RETURNED -> "历史费用已退回 · 待转外部流程";
            case CommissionDO.STATUS_DESIGNING -> !readyToComplete(commission)
                    ? "设计中 · 设计费办理中" : "设计中";
            case CommissionDO.STATUS_DESIGN_COMPLETED -> {
                yield readyToDispatch(commission)?"设计已完成":"设计已完成 · 设计费待付足并核收";
            }
            case CommissionDO.STATUS_DISPATCHED -> "成果已下发，待收图移交";
            case CommissionDO.STATUS_HANDOVER_SUBMITTED -> "移交待工程接收";
            case CommissionDO.STATUS_HANDOVER_RETURNED -> "移交被退回，待补充资料";
            case CommissionDO.STATUS_ACCEPTED -> "工程已接收";
            default -> commission.getStatus();
        };
    }

    /** 当前岗位此刻可执行的命令 */
    public List<String> allowedActions(PostSnapshot post, CommissionDO commission) {
        java.util.ArrayList<String> actions = new java.util.ArrayList<>();
        String status = commission.getStatus();
        boolean writable = access.canWrite(post, commission);
        if (ExternalCommissionService.external(commission)) {
            if (writable && java.util.Set.of(CommissionDO.STATUS_EXTERNAL_PENDING,CommissionDO.STATUS_EXTERNAL_READY,
                    CommissionDO.STATUS_HANDOVER_RETURNED).contains(status)) {
                actions.add("external-entrust-register"); actions.add("external-results-register");
                if (commission.getExternalResultVersion()!=null) actions.add("external-packet-submit");
            }
            return actions;
        }
        boolean settled = !simulatedPayments.blocked(commission) || simulatedPayments.reviewedForSettlement(commission)
                && settlement(commission).readyToDispatch();
        if (writable && settled && ExternalCommissionService.CONVERTIBLE.contains(status)) {
            actions.add("external-entrust-convert");
        }
        if (writable && settled && Set.of(CommissionDO.STATUS_DISPATCHED, CommissionDO.STATUS_HANDOVER_RETURNED).contains(status)) {
            actions.add("packet-submit");
        }
        if (CommissionDO.STATUS_HANDOVER_SUBMITTED.equals(status)) {
            handoverMapper.selectByCommission(commission.getId()).stream()
                    .filter(h -> Objects.equals(commission.getHandoverVersion(),h.getVersion()))
                    .filter(h -> CommissionHandoverDO.STATUS_PENDING.equals(h.getStatus()))
                    .filter(h -> access.isHandoverManager(post, commission.getCompanyId(), h.getManagerUserId()))
                    .findAny().ifPresent(h -> {
                        actions.add("packet-return");
                        if (settled) actions.add("packet-receive");
                    });
        }
        return actions;
    }

}
