package cn.iocoder.yudao.module.franchise.commission;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.api.EngineeringPort;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionDO;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionHandoverDO;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionSubmissionDO;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionSubmissionMapper;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionHandoverMapper;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionMapper;
import cn.iocoder.yudao.module.franchise.crm.CustomerService;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerMapper;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerSchemeDO;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.platform.command.Revisions;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.commission.enums.FranchiseCommissionErrorCodes.*;

/**
 * 收图移交（sales-flow.js packet）：业务提交五项资料并指定工程负责人，负责人核查接收或退回。
 */
@Service
public class HandoverService {

    public record PacketCommand(int dispatchSeq, String resultFile, String drawingFile, String reviewFile,
                                String confirmationFile, String drawingVersion, LocalDate confirmationDate,
                                String note, long managerUserId, boolean confirmed,Long confirmationAssetId) {
        public PacketCommand(int seq,String result,String drawing,String review,String confirmation,String version,
                             LocalDate date,String note,long manager,boolean confirmed) {
            this(seq,result,drawing,review,confirmation,version,date,note,manager,confirmed,null);
        }
    }

    public record DecisionCommand(String reason, boolean confirmed) {
    }

    private final CommissionMapper commissionMapper;
    private final CommissionHandoverMapper handoverMapper;
    private final StaffPostService staffPostService;
    private final FranchiseCommands commands;
    private final CommissionAccess access;
    private final CommissionService commissionService;
    private final CustomerMapper customerMapper;
    private final CustomerService customerService;
    private final CommissionSubmissionMapper submissionMapper;
    private final EngineeringPort engineering;
    private final cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort saleContracts;
    @jakarta.annotation.Resource private CommissionManifestService manifests;
    @jakarta.annotation.Resource private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @jakarta.annotation.Resource private cn.iocoder.yudao.module.platform.org.SystemDirectory directory;
    public record Receiver(long userId,String name,String roleCode) { }
    public List<Receiver> receivers(PostSnapshot actor,long commissionId) {
        var commission=commissionService.requireWritable(actor,commissionId);
        var posts=new java.util.ArrayList<>(staffPostService.listActivePostsInOrg(commission.getCompanyId(),FranchiseRoles.ENGINEER));
        posts.addAll(staffPostService.listActivePostsInOrg(commission.getCompanyId(),FranchiseRoles.PROJECT_MANAGER));
        var users=directory.usersByIds(posts.stream().map(StaffPostDO::getUserId).distinct().toList());
        return users.stream().filter(u->u.active() && u.userId()!=actor.userId()).map(u->new Receiver(u.userId(),u.name(),
                posts.stream().filter(p->p.getUserId()==u.userId()).findFirst().orElseThrow().getRoleCode())).toList();
    }

    public HandoverService(CommissionMapper commissionMapper, CommissionHandoverMapper handoverMapper,
                           StaffPostService staffPostService, FranchiseCommands commands, CommissionAccess access,
                           CommissionService commissionService, CustomerMapper customerMapper,
                           CustomerService customerService, CommissionSubmissionMapper submissionMapper,
                           EngineeringPort engineering,
                           cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort saleContracts) {
        this.commissionMapper = commissionMapper;
        this.handoverMapper = handoverMapper;
        this.staffPostService = staffPostService;
        this.commands = commands;
        this.access = access;
        this.commissionService = commissionService;
        this.customerMapper = customerMapper;
        this.customerService = customerService;
        this.submissionMapper = submissionMapper;
        this.engineering = engineering;
        this.saleContracts = saleContracts;
    }

    /** 提交移交（首次与退回后重提同一命令）：请求的 dispatchSeq 必须等于当前有效下发 */
    @Transactional(rollbackFor = Exception.class)
    public CommissionService.Detail submit(PostSnapshot post, long commissionId, PacketCommand command,
                                           String idempotencyKey) {
        idempotencyKey=lock(post,idempotencyKey);
        if(replayed(post,idempotencyKey,command,commissionId,"packet-submit")) return commissionService.get(post,commissionId);
        CommissionDO commission = commissionMapper.selectById(commissionId);
        if (commission == null || !access.canWrite(post, commission)) {
            throw exception(COMMISSION_NOT_FOUND);
        }
        if (!CommissionDO.STATUS_DISPATCHED.equals(commission.getStatus())
                && !CommissionDO.STATUS_HANDOVER_RETURNED.equals(commission.getStatus())) {
            throw exception(COMMISSION_STATE_INVALID);
        }
        PacketCommand input = normalize(command, commission);
        var manifest=manifests.require(commissionId,input.dispatchSeq());
        if(input.confirmationAssetId()==null) throw exception(HANDOVER_INPUT_INVALID);
        manifests.validate(post,commission.getCompanyId(),input.confirmationAssetId(),"CONTRACT_ATTACHMENT");
        StaffPostDO manager = requireManager(post, commission.getCompanyId(), input.managerUserId());
        Integer maxVersion = handoverMapper.selectMaxVersion(commissionId);
        CommissionHandoverDO handover = new CommissionHandoverDO()
                .setCompanyId(commission.getCompanyId()).setCommissionId(commissionId)
                .setVersion(maxVersion == null ? 1 : maxVersion + 1)
                .setDispatchSeq(input.dispatchSeq())
                .setResultFile(CommissionManifestService.fileRef(commissionId,input.dispatchSeq(),"RESULT",manifest.resultAssetId(),"正式设计成果"))
                .setDrawingFile(CommissionManifestService.fileRef(commissionId,input.dispatchSeq(),"DRAWING",manifest.drawingAssetId(),"正式施工图"))
                .setReviewFile(CommissionManifestService.fileRef(commissionId,input.dispatchSeq(),"REVIEW",manifest.reviewAssetId(),"专业审核依据"))
                .setConfirmationFile(CommissionManifestService.fileRef(commissionId,input.dispatchSeq(),"CONFIRMATION",input.confirmationAssetId(),"客户确认凭据"))
                .setConfirmationAssetId(input.confirmationAssetId())
                .setDrawingVersion(manifest.drawingVersion()).setConfirmationDate(input.confirmationDate())
                .setNote(input.note()).setManagerUserId(manager.getUserId())
                .setStatus(CommissionHandoverDO.STATUS_PENDING)
                // M06-T03：提交时冻结当时有效下发方案版本（后续变更留版本不覆盖）
                .setFrozenSchemeVersion(commission.getDispatchedSchemeVersion())
                .setSubmittedByUserId(post.userId());
        handoverMapper.insert(handover);
        long revision = commission.getRevision();
        Revisions.requireUpdated(commissionMapper.updateByRevision(commissionId, revision, u -> u
                .set(CommissionDO::getStatus, CommissionDO.STATUS_HANDOVER_SUBMITTED)
                .set(CommissionDO::getHandoverVersion, handover.getVersion())));
        commands.record(post, CommissionService.AGGREGATE, commissionId, revision + 1,
                "packet-submit", input, idempotencyKey);
        return commissionService.get(post, commissionId);
    }

    /** 工程负责人退回移交（原因必填） */
    @Transactional(rollbackFor = Exception.class)
    public CommissionService.Detail returnPacket(PostSnapshot post, long commissionId, DecisionCommand command,
                                                 String idempotencyKey) {
        decide(post, commissionId, command, false, idempotencyKey, "packet-return");
        return commissionService.get(post, commissionId);
    }

    /** 工程负责人核查接收 */
    @Transactional(rollbackFor = Exception.class)
    public CommissionService.Detail receive(PostSnapshot post, long commissionId, DecisionCommand command,
                                            String idempotencyKey) {
        decide(post, commissionId, command, true, idempotencyKey, "packet-receive");
        return commissionService.get(post, commissionId);
    }

    // ===================== 内部工具 =====================

    private void decide(PostSnapshot post, long commissionId, DecisionCommand command, boolean accept,
                        String idempotencyKey, String commandName) {
        idempotencyKey=lock(post,idempotencyKey);
        CommissionDO commission = commissionMapper.selectById(commissionId);
        if (commission == null) {
            throw exception(COMMISSION_NOT_FOUND);
        }
        // 幂等重放前置（阶段 2）：重放不适用状态门——首次办理后状态已流转（如 ACCEPTED），
        // 同键重放应直接返回首次结果（AC3 重复回调幂等依赖此顺序）。
        if (replayed(post,idempotencyKey,command,commissionId,commandName)) {
            return;
        }
        if (!CommissionDO.STATUS_HANDOVER_SUBMITTED.equals(commission.getStatus())) {
            throw exception(COMMISSION_STATE_INVALID);
        }
        CommissionHandoverDO handover = handoverMapper.selectByCommission(commissionId).stream()
                .filter(h -> commission.getHandoverVersion() != null && commission.getHandoverVersion().equals(h.getVersion()))
                .findFirst().orElseThrow(() -> exception(HANDOVER_NOT_FOUND));
        if (!access.isHandoverManager(post, commission.getCompanyId(), handover.getManagerUserId())) {
            throw exception(HANDOVER_DECISION_FORBIDDEN);
        }
        String reason = StrUtil.trim(command.reason());
        if (accept ? !command.confirmed() : StrUtil.isBlank(reason) || reason.length() > 500) {
            throw exception(accept ? HANDOVER_DECISION_FORBIDDEN : HANDOVER_RETURN_REASON_REQUIRED);
        }
        handoverMapper.updateById(new CommissionHandoverDO().setId(handover.getId())
                .setStatus(accept ? CommissionHandoverDO.STATUS_ACCEPTED : CommissionHandoverDO.STATUS_RETURNED)
                .setDecisionNote(reason).setDecidedByUserId(post.userId())
                .setDecidedAt(OffsetDateTime.now()));
        long revision = commission.getRevision();
        Revisions.requireUpdated(commissionMapper.updateByRevision(commissionId, revision, u -> u
                .set(CommissionDO::getStatus, accept ? CommissionDO.STATUS_ACCEPTED
                        : CommissionDO.STATUS_HANDOVER_RETURNED)));
        if (accept) {
            // 阶段 2（D-4）：接收成功同事务建档工程档案（内部 Port，幂等由 (company, source_commission_id) 兜底）
            engineering.receiveFromHandover(post, handoverCommand(post, commission, handover));
        }
        commands.record(post, CommissionService.AGGREGATE, commissionId, revision + 1, commandName, command, idempotencyKey);
    }

    /** 组装建档数据：客户称呼/城市/电话随客户行，面积/层数取最新预算方案（v3 蓝本 basis 口径） */
    private EngineeringPort.HandoverProject handoverCommand(PostSnapshot post, CommissionDO commission, CommissionHandoverDO handover) {
        CustomerDO customer = customerMapper.selectById(commission.getCustomerId());
        // R1 P1/R2：面积/层数按「下发冻结的实际提交版本」取参数（dispatch_seq=下发次数≠提交次数；
        // V1 下发后保存未确认 V2 预算不得进档案）；无对应提交（存量/异常）时 fallback 最新预算
        Integer frozenSubmissionVersion = commission.getDispatchedSubmissionVersion();
        CommissionSubmissionDO frozenSubmission = frozenSubmissionVersion == null ? null
                : submissionMapper.selectByCommission(commission.getId()).stream()
                        .filter(sub -> frozenSubmissionVersion.intValue() == sub.getVersion().intValue())
                        .findFirst().orElse(null);
        CustomerSchemeDO budget = frozenSubmission != null || customer == null ? null
                : customerService.latestScheme(customer.getId(), CustomerSchemeDO.KIND_BUDGET);
        String customerName = customer != null ? customer.getName() : commission.getCustomerName();
        String city = customer != null ? customer.getCity() : null;
        String phone = customer != null ? customer.getPhone() : null;
        String drawingVersion = handover.getDrawingVersion();
        // file_ref 列上限 1024：note 合法上限 1000 字，拼接后可越界致移交建档 SQL 异常——超长截断（评审十三 P3-4）
        String briefNote = handover.getNote() == null ? ""
                : handover.getNote().length() <= 900 ? handover.getNote() : handover.getNote().substring(0, 900) + "…";
        String briefFile = "{\"name\":\"电子交底记录\",\"text\":" + JsonUtils.toJsonString(briefNote) + "}";
        // M06-T03：解析统一建房合同（活跃 SIGNED；无则 null 兼容）——工程档案关联统一 contractId
        Long saleContractId = saleContracts.findActiveSignedId(post, commission.getCustomerId());
        return new EngineeringPort.HandoverProject(commission.getId(), commission.getCustomerId(), customerName,
                city, phone,
                frozenSubmission != null ? frozenSubmission.getArea() : (budget != null ? budget.getArea() : null),
                frozenSubmission != null ? frozenSubmission.getFloors() : (budget != null ? budget.getFloors() : null),
                handover.getManagerUserId(), drawingVersion, handover.getDrawingFile(),
                List.of(new EngineeringPort.HandoverDocument("正式设计成果", handover.getResultFile(), "DOCUMENT", drawingVersion),
                        new EngineeringPort.HandoverDocument("正式施工图", handover.getDrawingFile(), "DRAWING", drawingVersion),
                        new EngineeringPort.HandoverDocument("线下专业审核依据", handover.getReviewFile(), "DOCUMENT", drawingVersion),
                        new EngineeringPort.HandoverDocument("客户确认凭据", handover.getConfirmationFile(), "DOCUMENT", drawingVersion),
                        new EngineeringPort.HandoverDocument("电子交底", briefFile, "DOCUMENT", drawingVersion)),
                handover.getNote(), saleContractId, handover.getFrozenSchemeVersion());
    }

    /** 接收人：同组织有效工程线任职（engineer / project-manager，sales-flow.js:106），且不是提交人本人 */
    private StaffPostDO requireManager(PostSnapshot post, long storeOrgId, long targetUserId) {
        if (targetUserId == post.userId()) {
            throw exception(HANDOVER_TARGET_INVALID);
        }
        List<StaffPostDO> engineers = staffPostService.listActivePostsInOrg(storeOrgId, FranchiseRoles.ENGINEER);
        List<StaffPostDO> managers = staffPostService.listActivePostsInOrg(storeOrgId, FranchiseRoles.PROJECT_MANAGER);
        StaffPostDO match = engineers.stream().filter(p -> p.getUserId() == targetUserId).findFirst()
                .orElse(managers.stream().filter(p -> p.getUserId() == targetUserId).findFirst().orElse(null));
        if (match == null) {
            throw exception(HANDOVER_TARGET_INVALID);
        }
        return match;
    }

    private static PacketCommand normalize(PacketCommand c, CommissionDO commission) {
        int currentDispatchSeq = commission.getDispatchSeq() == null ? 0 : commission.getDispatchSeq();
        if (!c.confirmed() || c.dispatchSeq() != currentDispatchSeq
                || c.resultFile()!=null || c.drawingFile()!=null || c.reviewFile()!=null || c.confirmationFile()!=null
                || c.confirmationAssetId()==null
                || c.confirmationDate() == null || c.confirmationDate().isAfter(LocalDate.now())
                || StrUtil.isBlank(c.note()) || c.note().length() > 1000) {
            throw exception(HANDOVER_INPUT_INVALID);
        }
        return c;
    }

    private String lock(PostSnapshot post,String key) {
        key=commands.requireIdempotencyKey(key);
        long lock=cn.iocoder.yudao.module.franchise.org.FranchiseCommandKeys.lockKey(
                new cn.iocoder.yudao.module.franchise.org.FranchiseCommandKeys.PostKeyScope("franchise",post.userId()),key);
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(?)) s",Integer.class,lock);
        return key;
    }
    private boolean replayed(PostSnapshot post,String key,Object input,long id,String action) {
        var replay=commands.replayed(post,key,input);
        if(replay.isEmpty()) return false;
        if(replay.get().getAggregateId()!=id || !action.equals(replay.get().getCommand())) throw exception(
                cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED);
        return true;
    }

}
