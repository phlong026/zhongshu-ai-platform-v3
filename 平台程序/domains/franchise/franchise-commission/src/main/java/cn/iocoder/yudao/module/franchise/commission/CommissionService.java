package cn.iocoder.yudao.module.franchise.commission;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
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
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.workflow.ApprovalRequest;
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
    private final StaffPostService staffPostService;
    private final ApprovalService approvalService;
    private final FranchiseCommands commands;
    private final CommissionAccess access;
    @jakarta.annotation.Resource private cn.iocoder.yudao.module.franchise.api.DesignFeeSettlementPort settlement;
    @jakarta.annotation.Resource private DesignPricingService pricing;
    @jakarta.annotation.Resource private CommissionSimulatedPayments simulatedPayments;
    @jakarta.annotation.Resource private CommissionManifestService manifests;
    @jakarta.annotation.Resource private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private boolean simulationAllowed(CommissionDO commission) {
        return simulatedPayments.allowed(commission.getCompanyId(), commission.getDesignOrgId());
    }

    public record FeeOffer(String mode,Integer ruleVersion,BigDecimal area,Long rateCents,long totalCents,
                           long firstCents,long tailCents,String scope,String recipient,boolean simulationAllowed) { }
    public record Context(long customerId,Long commissionId,CustomerSchemeDO latestBudget,FeeOffer feeOffer,
                          Detail commission,List<String> allowedActions,String customerName,String city,boolean canPrepareBudget,java.util.Map<String,Object> customerInfo,java.util.Map<String,String> schemeBasis) { }

    public Context context(PostSnapshot post,long customerId) {
        CustomerDO customer=customerMapper.selectById(customerId);
        if(customer==null || !CustomerAccess.canRead(post,customer)) throw exception(COMMISSION_NOT_FOUND);
        var c=commissionMapper.selectByCustomer(customerId);
        var budget=customerService.latestScheme(customerId,CustomerSchemeDO.KIND_BUDGET);
        FeeOffer offer=null;
        long designOrgId = soleHeadquarters();
        var policy=pricing.current(designOrgId);
        if(policy!=null && budget!=null && budget.getArea()!=null) offer=new FeeOffer("AREA_FULL",policy.version(),budget.getArea(),policy.rateCents(),
                DesignPricingService.totalCents(budget.getArea(),policy.rateCents()),0,0,policy.scope(),policy.recipient(),simulatedPayments.allowed(customer.getCompanyId(),designOrgId));
        else {
            var legacy=policyOf(post.orgId());
            if(legacy!=null) offer=new FeeOffer("LEGACY_SPLIT",legacy.getVersion(),budget==null?null:budget.getArea(),null,
                    legacy.getTotalCents(),legacy.getFirstCents(),legacy.getTotalCents()-legacy.getFirstCents(),legacy.getScope(),legacy.getRecipient(),false);
        }
        boolean writable=CustomerAccess.canWrite(post,customer) && budget!=null && (c==null || CommissionDO.STATUS_FEE_RETURNED.equals(c.getStatus()));
        return new Context(customerId,c==null?null:c.getId(),budget,offer,c==null?null:detail(post,c),writable?List.of("commission-submit"):List.of(),customer.getName(),customer.getCity(),CustomerAccess.canWrite(post,customer),CommissionBrief.customerInfo(customer),schemeBasis(customerId,budget));
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

    void requireSimulationScope(CommissionDO commission) {
        if (simulatedPayments.blocked(commission)) throw exception(SIMULATION_FORBIDDEN);
    }
    public boolean readyToComplete(CommissionDO c) {
        var order = simulatedPayments.read(c);
        return order == null ? settlement(c).readyToComplete() : simulationAllowed(c) && order.paid();
    }
    public boolean readyToDispatch(CommissionDO c) {
        var order = simulatedPayments.read(c);
        return order == null ? settlement(c).readyToDispatch() : simulationAllowed(c) && order.paid();
    }
    public record SimulatePayCommand(String channel,boolean confirmed) { }
    @Transactional(rollbackFor=Exception.class)
    public Detail simulatePay(PostSnapshot post,long commissionId,SimulatePayCommand input,String key){
        var c=requireWritable(post,commissionId);
        if (!simulationAllowed(c)) throw exception(SIMULATION_FORBIDDEN);
        key=commandLock(post,key);
        if(replayed(post,key,input,commissionId,"commission-simulate-pay"))return get(post,commissionId);
        if(input==null||!input.confirmed())throw exception(SUBMISSION_INPUT_INVALID);
        requireState(c,CommissionDO.STATUS_DESIGNING);
        if(simulatedPayments.read(c)==null)throw exception(COMMISSION_STATE_INVALID);
        simulatedPayments.pay(c,input.channel(),post);
        Revisions.requireUpdated(commissionMapper.updateByRevision(commissionId,c.getRevision(),u->u.set(CommissionDO::getStatus,c.getStatus())));
        commands.record(post,AGGREGATE,commissionId,c.getRevision()+1,"commission-simulate-pay",input,key);
        return get(post,commissionId);
    }
    public CommissionSimulatedPayments.Order paymentReceipt(PostSnapshot post,long commissionId){
        var c=commissionMapper.selectById(commissionId);
        boolean finance=c!=null&&Set.of(FranchiseRoles.FINANCE,FranchiseRoles.FINANCE_LEAD).contains(post.roleCode())&&(post.orgId()==c.getCompanyId()||post.orgId()==c.getDesignOrgId());
        if(c==null||!finance&&!access.canRead(post,c))throw exception(COMMISSION_NOT_FOUND);
        var order=simulatedPayments.read(c);
        if(order==null)throw exception(COMMISSION_NOT_FOUND);
        return access.isDesignAdminOf(post,c)?order.designView():order;
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
                             StaffPostService staffPostService, ApprovalService approvalService,
                             FranchiseCommands commands, CommissionAccess access) {
        this.commissionMapper = commissionMapper;
        this.submissionMapper = submissionMapper;
        this.progressMapper = progressMapper;
        this.handoverMapper = handoverMapper;
        this.policyMapper = policyMapper;
        this.customerMapper = customerMapper;
        this.customerService = customerService;
        this.orgService = orgService;
        this.staffPostService = staffPostService;
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
        return commissionMapper.selectPage(page, access.visibleTo(post,
                visibleCustomerIds(post), managedCommissionIds(post)), keyword);
    }

    /** 当前岗位可见的全部委托（待办即时计算用） */
    public java.util.List<CommissionDO> listVisible(PostSnapshot post) {
        return commissionMapper.selectVisible(access.visibleTo(post,
                visibleCustomerIds(post), managedCommissionIds(post)));
    }

    public CommissionDO requireReadable(PostSnapshot post, long commissionId) {
        CommissionDO commission = commissionMapper.selectById(commissionId);
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
        return new Detail(commission, submissions, progressMapper.selectByCommission(commission.getId()),
                handoverMapper.selectByCommission(commission.getId()), approval,
                allowedActions(post, commission), displayStatus(commission),designAdmin||simulatedPayments.read(commission)!=null?null:settlement(commission),manifests.list(commission.getId()),designAdmin&&simulatedPayments.read(commission)!=null?simulatedPayments.read(commission).designView():simulatedPayments.read(commission));
    }

    // ===================== 命令 =====================

    /** 提交 / 重提委托：匹配当前有效标准费用直接进入设计；不一致走老板审批 */
    @Transactional(rollbackFor = Exception.class)
    public Detail submit(PostSnapshot post, long customerId, SubmitCommand command, String idempotencyKey) {
        CustomerDO customer = customerService.requireWritable(post, customerId);
        idempotencyKey=commandLock(post,idempotencyKey);
        SubmitCommand input = normalize(command);
        var replay=commands.replayed(post,idempotencyKey,input);
        if(replay.isPresent()) {
            var previous=commissionMapper.selectById(replay.get().getAggregateId());
            if(previous==null || previous.getCustomerId()!=customerId || !"commission-submit".equals(replay.get().getCommand()))
                throw exception(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED);
            return get(post,previous.getId());
        }
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?,0))) s",Integer.class,"commission-customer:"+customerId);
        CommissionDO existing = commissionMapper.selectByCustomer(customerId);
        if (existing != null && !CommissionDO.STATUS_FEE_RETURNED.equals(existing.getStatus())) {
            throw exception(COMMISSION_ALREADY_EXISTS);
        }
        long designOrgId = soleHeadquarters();
        boolean simulation = "SIMULATION".equals(input.paymentMode());
        if (simulation && !simulatedPayments.allowed(post.orgId(), designOrgId)) throw exception(SIMULATION_FORBIDDEN);
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?,0))) s",Integer.class,"design-pricing:"+designOrgId);
        CustomerSchemeDO budget = customerService.latestScheme(customerId, CustomerSchemeDO.KIND_BUDGET);
        if (budget == null) {
            throw exception(SUBMISSION_BUDGET_REQUIRED);
        }
        if(!java.util.Objects.equals(input.budgetVersion(),budget.getVersion())) throw exception(SUBMISSION_VERSION_STALE);
        var plan=matchingScheme(customerId,budget,CustomerSchemeDO.KIND_PLAN);
        var facade=matchingScheme(customerId,budget,CustomerSchemeDO.KIND_FACADE);
        if(input.planVersion()!=null&&(plan==null||!input.planVersion().equals(plan.getVersion()))||input.facadeVersion()!=null&&(facade==null||!input.facadeVersion().equals(facade.getVersion())))throw exception(SUBMISSION_VERSION_STALE);
        boolean areaFull="AREA_FULL".equals(input.mode());
        if(!areaFull) jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?,0))) s",Integer.class,"design-policy:"+post.orgId());
        var areaPolicy=areaFull?pricing.current(designOrgId):null;
        if(areaFull && (areaPolicy==null || !java.util.Objects.equals(areaPolicy.version(),input.feeRuleVersion())
                || !areaPolicy.scope().equals(input.scope()))) throw exception(SUBMISSION_VERSION_STALE);
        long totalCents = areaFull?DesignPricingService.totalCents(budget.getArea(),areaPolicy.rateCents()):DesignPolicyService.cents(input.total());
        long firstCents = areaFull?0:DesignPolicyService.cents(input.first());
        if(input.attachmentAssetId()!=null) manifests.validate(post,post.orgId(),input.attachmentAssetId(),"CONTRACT_ATTACHMENT");

        boolean standard = areaFull || matchesActivePolicy(post.orgId(), input, totalCents, firstCents);
        boolean exception = !standard;
        if (exception) {
            requireBossPresent(post);
        }

        CommissionDO commission;
        long eventRevision;
        if (existing == null) {
            commission = new CommissionDO().setCompanyId(post.orgId()).setDesignOrgId(designOrgId)
                    .setCustomerId(customerId).setCustomerName(customer.getName())
                    .setSubmissionVersion(1).setRevision(1L)
                    .setStatus(exception ? CommissionDO.STATUS_FEE_PENDING : CommissionDO.STATUS_DESIGNING);
            commissionMapper.insert(commission);
            eventRevision = 1;
        } else {
            commission = existing;
            requireState(commission, CommissionDO.STATUS_FEE_RETURNED);
            long revision = commission.getRevision();
            Revisions.requireUpdated(commissionMapper.updateByRevision(commission.getId(), revision, u -> u
                    .set(CommissionDO::getStatus, exception ? CommissionDO.STATUS_FEE_PENDING : CommissionDO.STATUS_DESIGNING)
                    .set(CommissionDO::getSubmissionVersion, commission.getSubmissionVersion() + 1)));
            eventRevision = revision + 1;
            commission.setSubmissionVersion(commission.getSubmissionVersion() + 1);
        }

        CommissionSubmissionDO submission = new CommissionSubmissionDO()
                .setCompanyId(post.orgId()).setDesignOrgId(designOrgId)
                .setCommissionId(commission.getId()).setVersion(commission.getSubmissionVersion())
                .setMode(input.mode()).setPricingVersion(areaFull?areaPolicy.version():null)
                .setRateCents(areaFull?areaPolicy.rateCents():null).setAttachmentAssetId(input.attachmentAssetId())
                .setText(input.text()).setScope(input.scope()).setAttachment(input.attachment())
                .setBudgetVersion(budget.getVersion()).setPlanVersion(plan==null?null:plan.getVersion()).setFacadeVersion(facade==null?null:facade.getVersion())
                .setWidth(budget.getWidth()).setDepth(budget.getDepth()).setFloors(budget.getFloors()).setArea(budget.getArea())
                .setSchemeSnapshot(schemeSnapshot(customerId, input))
                .setTotalCents(totalCents).setFirstCents(firstCents).setRecipient(areaFull?areaPolicy.recipient():input.recipient())
                .setAuthKind(standard ? CommissionSubmissionDO.AUTH_STANDARD : CommissionSubmissionDO.AUTH_EXCEPTION)
                .setSubmittedByUserId(post.userId()).setSubmittedByPostId(post.postId());
        if (standard && !areaFull) {
            var policy = policyOf(post.orgId());
            submission.setPolicyId(policy.getId()).setPolicyVersion(policy.getVersion());
        }
        submissionMapper.insert(submission);
        if(simulation)simulatedPayments.create(commission,totalCents);
        else if(standard) settlement.ensureInitialPayable(commission.getId(),commission.getSubmissionVersion(),post);

        if (exception) {
            approvalService.submit(new ApprovalRequest(FranchiseRoles.DOMAIN, post.orgId(), SUBJECT_TYPE,
                    commission.getId(), (long) commission.getSubmissionVersion(),
                    feeSignature(commission.getSubmissionVersion(), totalCents, firstCents, input.recipient(), input.scope()),
                    totalCents, FranchiseRoles.BOSS), post);
        }
        commands.record(post, AGGREGATE, commission.getId(), eventRevision, "commission-submit", input, idempotencyKey);
        return get(post, commission.getId());
    }

    /** 老板审批委托费用（走平台审批中心：不能自批、意见必填） */
    @Transactional(rollbackFor = Exception.class)
    public Detail feeDecide(PostSnapshot post, long commissionId, FeeDecisionCommand command, String idempotencyKey) {
        idempotencyKey=commandLock(post,idempotencyKey);
        if(replayed(post,idempotencyKey,command,commissionId,"commission-fee-decide")) return get(post,commissionId);
        CommissionDO commission = commissionMapper.selectById(commissionId);
        if (commission == null || commission.getCompanyId() != post.orgId()
                || !FranchiseRoles.atStore(post) || !FranchiseRoles.isBoss(post)) {
            throw exception(FEE_DECISION_FORBIDDEN);
        }
        requireState(commission, CommissionDO.STATUS_FEE_PENDING);
        String note = StrUtil.trim(command.note());
        if (StrUtil.isBlank(note) || note.length() > 500) {
            throw exception(FEE_DECISION_FORBIDDEN);
        }
        if (commands.replayed(post, idempotencyKey, command).isEmpty()) {
            ApprovalDO approval = approvalService.latest(FranchiseRoles.DOMAIN, SUBJECT_TYPE, commissionId)
                    .orElseThrow(() -> exception(COMMISSION_STATE_INVALID));
            approvalService.decide(approval.getId(), command.approve(), note, post);
            long revision = commission.getRevision();
            Revisions.requireUpdated(commissionMapper.updateByRevision(commissionId, revision, u -> u
                    .set(CommissionDO::getStatus, command.approve()
                            ? CommissionDO.STATUS_DESIGNING : CommissionDO.STATUS_FEE_RETURNED)));
            if(command.approve()) settlement.ensureInitialPayable(commissionId,commission.getSubmissionVersion(),post);
            commands.record(post, AGGREGATE, commissionId, revision + 1, "commission-fee-decide", command, idempotencyKey);
        }
        return get(post, commissionId);
    }

    /** 设计行政确认设计完成（首款就绪为前置；待处理修改意见一并视作已解决） */
    @Transactional(rollbackFor = Exception.class)
    public Detail complete(PostSnapshot post, long commissionId, CompleteCommand command, String idempotencyKey) {
        idempotencyKey=commandLock(post,idempotencyKey);
        if(replayed(post,idempotencyKey,command,commissionId,"design-complete")) return get(post,commissionId);
        CommissionDO commission = commissionMapper.selectById(commissionId);
        if (commission == null || !access.isDesignAdminOf(post, commission)) {
            throw exception(COMMISSION_FORBIDDEN);
        }
        requireState(commission, CommissionDO.STATUS_DESIGNING);
        if (!(simulatedPayments.read(commission)!=null?readyToComplete(commission):settlement.lockAndRead(commissionId,commission.getSubmissionVersion(),post).readyToComplete())) {
            throw exception(FIRST_PAYMENT_NOT_READY);
        }
        String note = progressNote(command.note(), command.confirmed());
        if (commands.replayed(post, idempotencyKey, command).isEmpty()) {
            appendProgress(commission, CommissionProgressDO.KIND_COMPLETE, note, null, null, post);
            resolvePendingFeedback(commission.getId());
            long revision = commission.getRevision();
            Revisions.requireUpdated(commissionMapper.updateByRevision(commissionId, revision, u -> u
                    .set(CommissionDO::getStatus, CommissionDO.STATUS_DESIGN_COMPLETED)));
            if(simulatedPayments.read(commission)==null)settlement.ensureTailPayable(commissionId,commission.getSubmissionVersion(),post);
            commands.record(post, AGGREGATE, commissionId, revision + 1, "design-complete", command, idempotencyKey);
        }
        return get(post, commissionId);
    }

    /** 设计行政下发成果（尾款就绪为前置；DISPATCH 的 seq 即下发版本） */
    @Transactional(rollbackFor = Exception.class)
    public Detail dispatch(PostSnapshot post, long commissionId, DispatchCommand command, String idempotencyKey) {
        idempotencyKey=commandLock(post,idempotencyKey);
        if(replayed(post,idempotencyKey,command,commissionId,"design-dispatch")) return get(post,commissionId);
        CommissionDO commission = commissionMapper.selectById(commissionId);
        if (commission == null || !access.isDesignAdminOf(post, commission)) {
            throw exception(COMMISSION_FORBIDDEN);
        }
        requireState(commission, CommissionDO.STATUS_DESIGN_COMPLETED);
        if (!(simulatedPayments.read(commission)!=null?readyToDispatch(commission):settlement.lockAndRead(commissionId,commission.getSubmissionVersion(),post).readyToDispatch())) {
            throw exception(TAIL_PAYMENT_NOT_READY);
        }
        String note = progressNote(command.note(), command.confirmed());
        String recipient = StrUtil.trimToNull(command.recipient());
        if (recipient == null || recipient.length() > 64) {
            throw exception(PROGRESS_INPUT_INVALID);
        }
        if (commands.replayed(post, idempotencyKey, command).isEmpty()) {
            if(command.resultAssetId()==null || command.drawingAssetId()==null || command.reviewAssetId()==null) throw exception(PROGRESS_INPUT_INVALID);
            int seq = appendProgress(commission, CommissionProgressDO.KIND_DISPATCH, note, recipient, null, post);
            manifests.append(post,commission,seq,command.resultAssetId(),command.drawingAssetId(),command.reviewAssetId(),command.drawingVersion());
            long revision = commission.getRevision();
            // M05-T04：确认下发时冻结最新提交的设计方案版本快照
            var latestSubmission = submissionMapper.selectLatestByCommissionId(commissionId);
            String schemeVer = latestSubmission != null
                    ? "P" + latestSubmission.getPlanVersion() + "/B" + latestSubmission.getBudgetVersion() + "/F" + latestSubmission.getFacadeVersion()
                    : null;
            Revisions.requireUpdated(commissionMapper.updateByRevision(commissionId, revision, u -> {
                u.set(CommissionDO::getStatus, CommissionDO.STATUS_DISPATCHED)
                 .set(CommissionDO::getDispatchSeq, seq)
                 .set(CommissionDO::getDispatchedSchemeVersion, schemeVer)
                 // M06-T03/R2：冻结下发实际使用的提交版本（移交包按此取参数，不按 seq 猜）
                 .set(CommissionDO::getDispatchedSubmissionVersion,
                         latestSubmission == null ? null : latestSubmission.getVersion());
            }));
            commands.record(post, AGGREGATE, commissionId, revision + 1, "design-dispatch", command, idempotencyKey);
        }
        return get(post, commissionId);
    }

    /** 业务登记修改意见：回到设计中；当前有效下发清空，修改意见针对原下发版本 */
    @Transactional(rollbackFor = Exception.class)
    public Detail feedback(PostSnapshot post, long commissionId, FeedbackCommand command, String idempotencyKey) {
        idempotencyKey=commandLock(post,idempotencyKey);
        if(replayed(post,idempotencyKey,command,commissionId,"commission-feedback")) return get(post,commissionId);
        CommissionDO commission = requireWritable(post, commissionId);
        requireState(commission, CommissionDO.STATUS_DISPATCHED);
        requireSimulationScope(commission);
        String note = StrUtil.trim(command.note());
        if (StrUtil.isBlank(note) || note.length() > 500) {
            throw exception(FEEDBACK_INPUT_INVALID);
        }
        if (commands.replayed(post, idempotencyKey, command).isEmpty()) {
            CommissionProgressDO record = new CommissionProgressDO()
                    .setCompanyId(commission.getCompanyId()).setDesignOrgId(commission.getDesignOrgId())
                    .setCommissionId(commissionId).setKind(CommissionProgressDO.KIND_FEEDBACK)
                    .setSeq(nextSeq(commissionId, CommissionProgressDO.KIND_FEEDBACK))
                    .setNote(note).setFeedbackStatus(CommissionProgressDO.FEEDBACK_PENDING)
                    .setDispatchSeq(commission.getDispatchSeq())
                    .setActorUserId(post.userId()).setActorPostId(post.postId());
            progressMapper.insert(record);
            long revision = commission.getRevision();
            // 修改意见清空当前有效下发（R2 一并清 schemeVersion 遗留缺口——「当前有效下发清空」语义覆盖两列）
            Revisions.requireUpdated(commissionMapper.updateByRevision(commissionId, revision, u -> u
                    .set(CommissionDO::getStatus, CommissionDO.STATUS_DESIGNING)
                    .set(CommissionDO::getDispatchSeq, null)
                    .set(CommissionDO::getDispatchedSchemeVersion, null)
                    .set(CommissionDO::getDispatchedSubmissionVersion, null)));
            commands.record(post, AGGREGATE, commissionId, revision + 1, "commission-feedback", command, idempotencyKey);
        }
        return get(post, commissionId);
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

    /** 门店当前有效标准费用与本提交的总额、首款、收款方、交付范围全部一致（尾款=总额-首款，随之相同） */
    private boolean matchesActivePolicy(long orgId, SubmitCommand input, long totalCents, long firstCents) {
        var policy = policyOf(orgId);
        return policy != null && policy.getTotalCents() == totalCents && policy.getFirstCents() == firstCents
                && Objects.equals(policy.getRecipient(), input.recipient())
                && Objects.equals(policy.getScope(), input.scope());
    }

    private cn.iocoder.yudao.module.franchise.commission.dal.DesignPolicyDO policyOf(long orgId) {
        return policyMapper.selectActive(orgId);
    }

    /** 费用与标准不一致需老板审批；本店须存在「不是提交人」的有效老板任职 */
    private void requireBossPresent(PostSnapshot post) {
        List<StaffPostDO> bosses = staffPostService.listActivePostsInOrg(post.orgId(), FranchiseRoles.BOSS);
        if (bosses.stream().noneMatch(b -> b.getUserId() != post.userId())) {
            throw exception(BOSS_ABSENT);
        }
    }

    private long soleHeadquarters() {
        List<Long> ids = orgService.listActiveOrgIds(FranchiseRoles.ORG_HEADQUARTERS);
        if (ids.size() != 1) {
            throw exception(DESIGN_ORG_REQUIRED);
        }
        return ids.get(0);
    }

    private static String feeSignature(int submissionVersion, long totalCents, long firstCents,
                                       String recipient, String scope) {
        return DigestUtil.sha256Hex(submissionVersion + "|" + totalCents + "|" + firstCents + "|"
                + (totalCents - firstCents) + "|" + recipient + "|" + scope);
    }

    private int appendProgress(CommissionDO commission, String kind, String note, String recipient,
                               Integer feedbackDispatchSeq, PostSnapshot post) {
        CommissionProgressDO record = new CommissionProgressDO()
                .setCompanyId(commission.getCompanyId()).setDesignOrgId(commission.getDesignOrgId())
                .setCommissionId(commission.getId()).setKind(kind)
                .setSeq(nextSeq(commission.getId(), kind)).setNote(note).setRecipient(recipient)
                .setDispatchSeq(feedbackDispatchSeq)
                .setActorUserId(post.userId()).setActorPostId(post.postId());
        progressMapper.insert(record);
        return record.getSeq();
    }

    private int nextSeq(long commissionId, String kind) {
        Integer max = progressMapper.selectMaxSeq(commissionId, kind);
        return max == null ? 1 : max + 1;
    }

    private void resolvePendingFeedback(long commissionId) {
        progressMapper.selectByCommission(commissionId).stream()
                .filter(p -> CommissionProgressDO.KIND_FEEDBACK.equals(p.getKind())
                        && CommissionProgressDO.FEEDBACK_PENDING.equals(p.getFeedbackStatus()))
                .forEach(p -> progressMapper.updateById(new CommissionProgressDO()
                        .setId(p.getId()).setFeedbackStatus(CommissionProgressDO.FEEDBACK_RESOLVED)));
    }

    private static void requireState(CommissionDO commission, String expected) {
        if (!expected.equals(commission.getStatus())) {
            throw exception(COMMISSION_STATE_INVALID);
        }
    }

    private static String progressNote(String note, boolean confirmed) {
        String value = StrUtil.trim(note);
        if (!confirmed || StrUtil.isBlank(value) || value.length() > 1000) {
            throw exception(PROGRESS_INPUT_INVALID);
        }
        return value;
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
        if(c.paymentMode()!=null&&(!"SIMULATION".equals(c.paymentMode())||!"AREA_FULL".equals(mode)))throw exception(SUBMISSION_INPUT_INVALID);
        return new SubmitCommand(text, scope, attachment, c.total(), c.first(), recipient,
                c.budgetVersion(), c.planVersion(), c.facadeVersion(), true,mode,c.feeRuleVersion(),c.attachmentAssetId(),CommissionBrief.validate(c.designBrief()),c.paymentMode());
    }

    private String schemeSnapshot(long customerId, SubmitCommand input) {
        StringBuilder snapshot = new StringBuilder("{");
        appendScheme(snapshot, customerId, CustomerSchemeDO.KIND_BUDGET);
        appendScheme(snapshot, customerId, CustomerSchemeDO.KIND_PLAN);
        appendScheme(snapshot, customerId, CustomerSchemeDO.KIND_FACADE);
        if(input.designBrief()!=null){
            var brief=new java.util.LinkedHashMap<String,Object>(input.designBrief());
            brief.put("customer",CommissionBrief.customerInfo(customerMapper.selectById(customerId)));
            var budget=customerService.latestScheme(customerId,CustomerSchemeDO.KIND_BUDGET);
            brief.put("budgetVersion",budget.getVersion());
            brief.put("buildingArea",budget.getArea());brief.put("width",budget.getWidth());brief.put("depth",budget.getDepth());brief.put("floors",budget.getFloors());
            if(snapshot.length()>1)snapshot.append(',');
            snapshot.append("\"designBrief\":").append(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(brief));
        }
        return snapshot.append("}").toString();
    }

    private void appendScheme(StringBuilder snapshot, long customerId, String kind) {
        CustomerSchemeDO scheme = customerService.latestScheme(customerId, kind);
        if(!CustomerSchemeDO.KIND_BUDGET.equals(kind))scheme=matchingScheme(customerId,customerService.latestScheme(customerId,CustomerSchemeDO.KIND_BUDGET),kind);
        if (scheme != null) {
            if (snapshot.length() > 1) {
                snapshot.append(',');
            }
            snapshot.append("\"").append(kind).append("\":")
                    .append(scheme.getPayload() == null ? "null" : scheme.getPayload());
        }
    }

    /** 可见客户集合：本岗位可读的客户的编号（列表过滤用） */
    private java.util.Set<Long> visibleCustomerIds(PostSnapshot post) {
        return java.util.Set.copyOf(customerMapper.selectVisible(CustomerAccess.visibleTo(post)).stream()
                .map(CustomerDO::getId).toList());
    }

    /** 本人是接收人的委托集合 */
    private java.util.Set<Long> managedCommissionIds(PostSnapshot post) {
        return java.util.Set.copyOf(handoverMapper.selectList(
                        new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<CommissionHandoverDO>()
                                .eq(CommissionHandoverDO::getManagerUserId, post.userId()))
                .stream().map(CommissionHandoverDO::getCommissionId).toList());
    }

    // ===================== 展示 =====================

    /** 显示状态：存储状态 + 首尾款进度（文案与小程序 sales-flow.js next() 一致） */
    public String displayStatus(CommissionDO commission) {
        var simulated=simulatedPayments.read(commission);
        if(simulated!=null&&!simulationAllowed(commission))return "历史模拟委托 · 暂停办理";
        if(simulated!=null&&!simulated.paid())return "待模拟付款登记";
        return switch (commission.getStatus()) {
            case CommissionDO.STATUS_FEE_PENDING -> "费用待老板审批";
            case CommissionDO.STATUS_FEE_RETURNED -> "费用被退回，待修改重提";
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
        if (CommissionDO.STATUS_FEE_RETURNED.equals(status) && writable) {
            actions.add("commission-submit");
        }
        if (CommissionDO.STATUS_FEE_PENDING.equals(status) && FranchiseRoles.atStore(post) && FranchiseRoles.isBoss(post)) {
            actions.add("fee-decide");
        }
        var simulated=simulatedPayments.read(commission);
        if (simulated != null && !simulationAllowed(commission)) return List.of();
        if(writable&&simulated!=null&&!simulated.paid())actions.add("commission-simulate-pay");
        boolean designAdmin = access.isDesignAdminOf(post, commission);
        if (CommissionDO.STATUS_DESIGNING.equals(status) && designAdmin && readyToComplete(commission)) {
            actions.add("design-complete");
        }
        if (CommissionDO.STATUS_DESIGN_COMPLETED.equals(status) && designAdmin && readyToDispatch(commission)) {
            actions.add("design-dispatch");
        }
        if (CommissionDO.STATUS_DISPATCHED.equals(status) && writable) {
            actions.add("commission-feedback");
            actions.add("packet-submit");
        }
        if (CommissionDO.STATUS_HANDOVER_RETURNED.equals(status) && writable) {
            actions.add("packet-submit");
        }
        if (CommissionDO.STATUS_HANDOVER_SUBMITTED.equals(status)) {
            handoverMapper.selectByCommission(commission.getId()).stream()
                    .filter(h -> CommissionHandoverDO.STATUS_PENDING.equals(h.getStatus()))
                    .filter(h -> access.isHandoverManager(post, commission.getCompanyId(), h.getManagerUserId()))
                    .findAny().ifPresent(h -> {
                        actions.add("packet-return");
                        actions.add("packet-receive");
                    });
        }
        return actions;
    }

}
