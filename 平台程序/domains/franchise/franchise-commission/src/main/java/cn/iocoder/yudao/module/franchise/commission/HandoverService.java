package cn.iocoder.yudao.module.franchise.commission;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.api.EngineeringPort;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionDO;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionHandoverDO;
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
                                String note, long managerUserId, boolean confirmed) {
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
    private final EngineeringPort engineering;

    public HandoverService(CommissionMapper commissionMapper, CommissionHandoverMapper handoverMapper,
                           StaffPostService staffPostService, FranchiseCommands commands, CommissionAccess access,
                           CommissionService commissionService, CustomerMapper customerMapper,
                           CustomerService customerService, EngineeringPort engineering) {
        this.commissionMapper = commissionMapper;
        this.handoverMapper = handoverMapper;
        this.staffPostService = staffPostService;
        this.commands = commands;
        this.access = access;
        this.commissionService = commissionService;
        this.customerMapper = customerMapper;
        this.customerService = customerService;
        this.engineering = engineering;
    }

    /** 提交移交（首次与退回后重提同一命令）：请求的 dispatchSeq 必须等于当前有效下发 */
    @Transactional(rollbackFor = Exception.class)
    public CommissionService.Detail submit(PostSnapshot post, long commissionId, PacketCommand command,
                                           String idempotencyKey) {
        CommissionDO commission = commissionMapper.selectById(commissionId);
        if (commission == null || !access.canWrite(post, commission)) {
            throw exception(COMMISSION_NOT_FOUND);
        }
        if (!CommissionDO.STATUS_DISPATCHED.equals(commission.getStatus())
                && !CommissionDO.STATUS_HANDOVER_RETURNED.equals(commission.getStatus())) {
            throw exception(COMMISSION_STATE_INVALID);
        }
        PacketCommand input = normalize(command, commission);
        if (commands.replayed(post, idempotencyKey, input).isPresent()) {
            return commissionService.get(post, commissionId);
        }
        StaffPostDO manager = requireManager(post, commission.getCompanyId(), input.managerUserId());
        Integer maxVersion = handoverMapper.selectMaxVersion(commissionId);
        CommissionHandoverDO handover = new CommissionHandoverDO()
                .setCompanyId(commission.getCompanyId()).setCommissionId(commissionId)
                .setVersion(maxVersion == null ? 1 : maxVersion + 1)
                .setDispatchSeq(input.dispatchSeq())
                .setResultFile(input.resultFile()).setDrawingFile(input.drawingFile())
                .setReviewFile(input.reviewFile()).setConfirmationFile(input.confirmationFile())
                .setDrawingVersion(input.drawingVersion()).setConfirmationDate(input.confirmationDate())
                .setNote(input.note()).setManagerUserId(manager.getUserId())
                .setStatus(CommissionHandoverDO.STATUS_PENDING)
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
        CommissionDO commission = commissionMapper.selectById(commissionId);
        if (commission == null) {
            throw exception(COMMISSION_NOT_FOUND);
        }
        // 幂等重放前置（阶段 2）：重放不适用状态门——首次办理后状态已流转（如 ACCEPTED），
        // 同键重放应直接返回首次结果（AC3 重复回调幂等依赖此顺序）。
        if (commands.replayed(post, idempotencyKey, command).isPresent()) {
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
            engineering.receiveFromHandover(post, handoverCommand(commission, handover));
        }
        commands.record(post, CommissionService.AGGREGATE, commissionId, revision + 1, commandName, command, idempotencyKey);
    }

    /** 组装建档数据：客户称呼/城市/电话随客户行，面积/层数取最新预算方案（v3 蓝本 basis 口径） */
    private EngineeringPort.HandoverProject handoverCommand(CommissionDO commission, CommissionHandoverDO handover) {
        CustomerDO customer = customerMapper.selectById(commission.getCustomerId());
        CustomerSchemeDO budget = customer == null ? null
                : customerService.latestScheme(customer.getId(), CustomerSchemeDO.KIND_BUDGET);
        String customerName = customer != null ? customer.getName() : commission.getCustomerName();
        String city = customer != null ? customer.getCity() : null;
        String phone = customer != null ? customer.getPhone() : null;
        String drawingVersion = handover.getDrawingVersion();
        // file_ref 列上限 1024：note 合法上限 1000 字，拼接后可越界致移交建档 SQL 异常——超长截断（评审十三 P3-4）
        String briefNote = handover.getNote() == null ? ""
                : handover.getNote().length() <= 900 ? handover.getNote() : handover.getNote().substring(0, 900) + "…";
        String briefFile = "{\"name\":\"电子交底记录\",\"text\":" + JsonUtils.toJsonString(briefNote) + "}";
        return new EngineeringPort.HandoverProject(commission.getId(), commission.getCustomerId(), customerName,
                city, phone, budget != null ? budget.getArea() : null, budget != null ? budget.getFloors() : null,
                handover.getManagerUserId(), drawingVersion, handover.getDrawingFile(),
                List.of(new EngineeringPort.HandoverDocument("正式设计成果", handover.getResultFile(), "DOCUMENT", drawingVersion),
                        new EngineeringPort.HandoverDocument("正式施工图", handover.getDrawingFile(), "DRAWING", drawingVersion),
                        new EngineeringPort.HandoverDocument("线下专业审核依据", handover.getReviewFile(), "DOCUMENT", drawingVersion),
                        new EngineeringPort.HandoverDocument("客户确认凭据", handover.getConfirmationFile(), "DOCUMENT", drawingVersion),
                        new EngineeringPort.HandoverDocument("电子交底", briefFile, "DOCUMENT", drawingVersion)),
                handover.getNote());
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
                || blank(c.resultFile()) || blank(c.drawingFile()) || blank(c.reviewFile()) || blank(c.confirmationFile())
                || StrUtil.isBlank(c.drawingVersion()) || c.drawingVersion().length() > 64
                || c.confirmationDate() == null || c.confirmationDate().isAfter(LocalDate.now())
                || StrUtil.isBlank(c.note()) || c.note().length() > 1000) {
            throw exception(HANDOVER_INPUT_INVALID);
        }
        return c;
    }

    private static boolean blank(String file) {
        return StrUtil.isBlank(file) || file.length() > 1024;
    }

}
