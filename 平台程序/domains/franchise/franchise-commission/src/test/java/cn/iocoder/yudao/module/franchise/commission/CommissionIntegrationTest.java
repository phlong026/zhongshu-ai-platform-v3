package cn.iocoder.yudao.module.franchise.commission;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.franchise.commission.CommissionService.CompleteCommand;
import cn.iocoder.yudao.module.franchise.commission.CommissionService.DispatchCommand;
import cn.iocoder.yudao.module.franchise.commission.CommissionService.FeeDecisionCommand;
import cn.iocoder.yudao.module.franchise.commission.CommissionService.FeedbackCommand;
import cn.iocoder.yudao.module.franchise.commission.CommissionService.SubmitCommand;
import cn.iocoder.yudao.module.franchise.commission.HandoverService.DecisionCommand;
import cn.iocoder.yudao.module.franchise.commission.HandoverService.PacketCommand;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionDO;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionHandoverDO;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionProgressDO;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionSubmissionDO;
import cn.iocoder.yudao.module.franchise.commission.dal.DesignPolicyDO;
import cn.iocoder.yudao.module.franchise.crm.CustomerService;
import cn.iocoder.yudao.module.franchise.crm.CustomerService.SchemeCommand;
import cn.iocoder.yudao.module.franchise.crm.LeadService;
import cn.iocoder.yudao.module.franchise.crm.LeadService.CaptureCommand;
import cn.iocoder.yudao.module.franchise.crm.LeadService.CaptureResult;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.test.FranchiseFixture.Store;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.workflow.ApprovalServiceImpl;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT;
import static cn.iocoder.yudao.module.franchise.commission.enums.FranchiseCommissionErrorCodes.*;
import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.*;
import static cn.iocoder.yudao.module.franchise.test.FranchiseAssertions.assertError;
import static cn.iocoder.yudao.module.platform.workflow.enums.PlatformWorkflowErrorCodes.APPROVAL_SELF_FORBIDDEN;
import static org.assertj.core.api.Assertions.assertThat;

@Import({CommissionService.class, HandoverService.class, DesignPolicyService.class, CommissionAccess.class,
        CommissionOrgScope.class, FranchiseCommands.class, CommandLogImpl.class, ApprovalServiceImpl.class,
        CustomerService.class, LeadService.class, cn.iocoder.yudao.module.franchise.crm.CrmOrgScope.class})
class CommissionIntegrationTest extends FranchisePgIntegrationTest {

    private static final AtomicLong PHONES = new AtomicLong(15_000_000_000L + System.nanoTime() % 800_000_000L);

    private static final BigDecimal TOTAL = new BigDecimal("680000");
    private static final BigDecimal FIRST = new BigDecimal("408000");
    private static final String SCOPE = "方案、施工图与交底";
    private static final String RECIPIENT = "总部财务";

    @Resource
    private LeadService leads;
    @Resource
    private CustomerService customers;
    @Resource
    private CommissionService commissions;
    @Resource
    private HandoverService handovers;
    @Resource
    private DesignPolicyService policies;

    private Store store;
    private PostSnapshot salesA1;
    private PostSnapshot boss;
    private PostSnapshot engineer;
    private PostSnapshot finance;
    private PostSnapshot designAdmin;
    private PostSnapshot otherStoreSales;

    // ===================== 完整主链（验收清单：登记客资 → 工程接收） =====================

    @Test
    void fullChainFromLeadToAcceptanceWithStandardFee() {
        long customerId = customerWithBudget();
        DesignPolicyDO policy = as(boss, () -> policies.save(boss,
                new DesignPolicyService.PolicyCommand(TOTAL, FIRST, SCOPE, RECIPIENT), null));

        var detail = as(salesA1, () -> commissions.submit(salesA1, customerId,
                submit(TOTAL, FIRST), null));

        assertThat(detail.commission().getStatus()).isEqualTo(CommissionDO.STATUS_DESIGNING);
        CommissionSubmissionDO submission = detail.submissions().get(0);
        assertThat(submission.getAuthKind()).isEqualTo(CommissionSubmissionDO.AUTH_STANDARD);
        assertThat(submission.getPolicyVersion()).isEqualTo(policy.getVersion());
        assertThat(submission.getSchemeSnapshot()).contains("BUDGET");
        assertThat(detail.displayStatus()).isEqualTo("设计中 · 首款办理中");

        // 首款未就绪不能确认设计完成
        assertError(() -> as(designAdmin, () -> commissions.complete(designAdmin,
                detail.commission().getId(), new CompleteCommand("设计完成", true), null)), FIRST_PAYMENT_NOT_READY);

        // 门店财务核实：首款就绪 → 设计完成
        as(finance, () -> commissions.markFirstReady(detail.commission().getId(), 1, finance));
        as(designAdmin, () -> commissions.complete(designAdmin, detail.commission().getId(),
                new CompleteCommand("线下设计与内部审核已完成", true), null));

        // 尾款：先付足、再总部核实；未就绪不能下发
        assertError(() -> as(designAdmin, () -> commissions.dispatch(designAdmin,
                detail.commission().getId(), dispatch(), null)), TAIL_PAYMENT_NOT_READY);
        as(finance, () -> commissions.markTailPaid(detail.commission().getId(), 1, finance));
        as(finance, () -> commissions.markTailReady(detail.commission().getId(), 1, finance));
        var dispatched = as(designAdmin, () -> commissions.dispatch(designAdmin,
                detail.commission().getId(), dispatch(), null));

        assertThat(dispatched.commission().getStatus()).isEqualTo(CommissionDO.STATUS_DISPATCHED);
        assertThat(dispatched.commission().getDispatchSeq()).isEqualTo(1);

        // 修改意见：回到设计中，下发版本清空；重新完成与下发不重收首尾款
        var feedback = as(salesA1, () -> commissions.feedback(salesA1, detail.commission().getId(),
                new FeedbackCommand("立面调整：门洞改双开"), null));
        assertThat(feedback.commission().getStatus()).isEqualTo(CommissionDO.STATUS_DESIGNING);
        assertThat(feedback.commission().getDispatchSeq()).isNull();

        as(designAdmin, () -> commissions.complete(designAdmin, detail.commission().getId(),
                new CompleteCommand("按修改意见重新完成", true), null));
        var reDispatched = as(designAdmin, () -> commissions.dispatch(designAdmin,
                detail.commission().getId(), dispatch(), null));
        assertThat(reDispatched.commission().getDispatchSeq()).isEqualTo(2);
        assertThat(reDispatched.progress()).extracting(CommissionProgressDO::getKind)
                .contains(CommissionProgressDO.KIND_COMPLETE, CommissionProgressDO.KIND_DISPATCH, CommissionProgressDO.KIND_FEEDBACK);

        // 移交 → 退回 → 重提 → 接收
        long commissionId = detail.commission().getId();
        as(salesA1, () -> handovers.submit(salesA1, commissionId, packet(2, engineer.userId()), null));

        assertError(() -> as(designAdmin, () -> handovers.returnPacket(designAdmin, commissionId,
                new DecisionCommand("无权操作", false), null)), HANDOVER_NOT_FOUND);
        assertError(() -> as(salesA1, () -> handovers.returnPacket(salesA1, commissionId,
                new DecisionCommand("非接收人", false), null)), HANDOVER_DECISION_FORBIDDEN);
        as(engineer, () -> handovers.returnPacket(engineer, commissionId,
                new DecisionCommand("客户确认日期与合同不符", false), null));

        var resubmitted = as(salesA1, () -> handovers.submit(salesA1, commissionId, packet(2, engineer.userId()), null));
        assertThat(resubmitted.commission().getStatus()).isEqualTo(CommissionDO.STATUS_HANDOVER_SUBMITTED);

        var accepted = as(engineer, () -> handovers.receive(engineer, commissionId,
                new DecisionCommand(null, true), null));
        assertThat(accepted.commission().getStatus()).isEqualTo(CommissionDO.STATUS_ACCEPTED);
        assertThat(accepted.handovers()).extracting(CommissionHandoverDO::getStatus)
                .containsExactly(CommissionHandoverDO.STATUS_ACCEPTED, CommissionHandoverDO.STATUS_RETURNED);
        assertThat(as(engineer, () -> commissions.requireReadable(engineer, commissionId))).isNotNull();
    }

    // ===================== 费用审批 =====================

    @Test
    void exceptionFeeGoesThroughBossApprovalAndReturn() {
        long customerId = customerWithBudget();
        var detail = as(salesA1, () -> commissions.submit(salesA1, customerId,
                submit(new BigDecimal("750000"), new BigDecimal("400000")), null));

        assertThat(detail.commission().getStatus()).isEqualTo(CommissionDO.STATUS_FEE_PENDING);
        assertThat(detail.submissions().get(0).getAuthKind()).isEqualTo(CommissionSubmissionDO.AUTH_EXCEPTION);
        assertThat(detail.feeApproval()).isNotNull();
        assertThat(detail.allowedActions()).isEmpty(); // 提交人此刻无事可办

        // 非老板不能审批
        assertError(() -> as(salesA1, () -> commissions.feeDecide(salesA1, detail.commission().getId(),
                new FeeDecisionCommand(true, "同意"), null)), FEE_DECISION_FORBIDDEN);

        var approved = as(boss, () -> commissions.feeDecide(boss, detail.commission().getId(),
                new FeeDecisionCommand(true, "费用合理"), null));
        assertThat(approved.commission().getStatus()).isEqualTo(CommissionDO.STATUS_DESIGNING);

        // 退回 → 修改重提
        long customerId2 = customerWithBudget();
        var second = as(salesA1, () -> commissions.submit(salesA1, customerId2,
                submit(new BigDecimal("800000"), new BigDecimal("300000")), null));
        var returned = as(boss, () -> commissions.feeDecide(boss, second.commission().getId(),
                new FeeDecisionCommand(false, "超出标准太多，按标准来"), null));
        assertThat(returned.commission().getStatus()).isEqualTo(CommissionDO.STATUS_FEE_RETURNED);

        as(boss, () -> policies.save(boss, new DesignPolicyService.PolicyCommand(TOTAL, FIRST, SCOPE, RECIPIENT), null));
        var resubmitted = as(salesA1, () -> commissions.submit(salesA1, customerId2,
                submit(TOTAL, FIRST), null));
        assertThat(resubmitted.commission().getStatus()).isEqualTo(CommissionDO.STATUS_DESIGNING);
        assertThat(resubmitted.commission().getSubmissionVersion()).isEqualTo(2);
    }

    @Test
    void selfApprovalIsRejectedAndAbsentBossBlocksSubmission() {
        // 提交人兼本店老板：以业务身份提交后，同一人以老板身份审批会被平台拒绝自批
        PostSnapshot bossPostOfSales = fixture.postFor(salesA1.userId(), store.orgId(), BOSS, null);
        long customerId = customerWithBudget();
        var detail = as(salesA1, () -> commissions.submit(salesA1, customerId,
                submit(new BigDecimal("900000"), new BigDecimal("500000")), null));

        assertError(() -> as(bossPostOfSales, () -> commissions.feeDecide(bossPostOfSales, detail.commission().getId(),
                new FeeDecisionCommand(true, "自己同意"), null)), APPROVAL_SELF_FORBIDDEN);

        // 另一家店：提交人自己是唯一老板且无他人可审 → 拒绝提交
        Store lone = fixture.store();
        PostSnapshot loneSales = fixture.post(lone.orgId(), SALES, lone.salesTeamA());
        fixture.postFor(loneSales.userId(), lone.orgId(), BOSS, null);
        long otherCustomer = as(loneSales, () -> captureCustomer(loneSales));
        as(loneSales, () -> customers.saveScheme(loneSales, otherCustomer,
                new SchemeCommand("BUDGET", "参考预算", "{}", new BigDecimal("13"), new BigDecimal("16"), 2,
                        new BigDecimal("400"), 68_000_000L), null));
        assertError(() -> as(loneSales, () -> commissions.submit(loneSales, otherCustomer,
                submit(new BigDecimal("900000"), new BigDecimal("500000")), null)), BOSS_ABSENT);
    }

    // ===================== 越权与状态守卫 =====================

    @Test
    void authorizationAndStateGuards() {
        long customerId = customerWithBudget();
        as(boss, () -> policies.save(boss, new DesignPolicyService.PolicyCommand(TOTAL, FIRST, SCOPE, RECIPIENT), null));
        var detail = as(salesA1, () -> commissions.submit(salesA1, customerId, submit(TOTAL, FIRST), null));
        long commissionId = detail.commission().getId();

        // 他组织不可见
        assertError(() -> as(otherStoreSales, () -> commissions.get(otherStoreSales, commissionId)), COMMISSION_NOT_FOUND);
        assertError(() -> as(otherStoreSales, () -> commissions.submit(otherStoreSales, customerId, submit(TOTAL, FIRST), null)),
                cn.iocoder.yudao.module.franchise.crm.enums.FranchiseCrmErrorCodes.CUSTOMER_NOT_FOUND);
        // 工程岗尚未被指定为接收人，看不到委托；老板只看不办
        assertError(() -> as(engineer, () -> commissions.feedback(engineer, commissionId,
                new FeedbackCommand("越权意见"), null)), COMMISSION_NOT_FOUND);
        assertError(() -> as(boss, () -> commissions.feedback(boss, commissionId,
                new FeedbackCommand("老板代办"), null)), COMMISSION_FORBIDDEN);
        // 重复提交
        assertError(() -> as(salesA1, () -> commissions.submit(salesA1, customerId, submit(TOTAL, FIRST), null)),
                COMMISSION_ALREADY_EXISTS);
        // 输入校验
        assertError(() -> as(salesA1, () -> commissions.submit(salesA1, customerId,
                submit(FIRST, TOTAL), null)), SUBMISSION_AMOUNT_INVALID);
        assertError(() -> as(salesA1, () -> commissions.submit(salesA1, customerId,
                submit(new BigDecimal("1000000000.01"), FIRST), null)), SUBMISSION_AMOUNT_INVALID);
        assertError(() -> as(salesA1, () -> commissions.submit(salesA1, customerId,
                new SubmitCommand(" ", SCOPE, null, TOTAL, FIRST, RECIPIENT, null, null, null, true), null)),
                SUBMISSION_INPUT_INVALID);

        // 移交目标不能是本人或非工程岗
        as(finance, () -> commissions.markFirstReady(commissionId, 1, finance));
        as(designAdmin, () -> commissions.complete(designAdmin, commissionId, new CompleteCommand("完成", true), null));
        as(finance, () -> commissions.markTailPaid(commissionId, 1, finance));
        as(finance, () -> commissions.markTailReady(commissionId, 1, finance));
        as(designAdmin, () -> commissions.dispatch(designAdmin, commissionId, dispatch(), null));
        assertError(() -> as(salesA1, () -> handovers.submit(salesA1, commissionId,
                packet(1, salesA1.userId()), null)), HANDOVER_TARGET_INVALID);
        assertError(() -> as(salesA1, () -> handovers.submit(salesA1, commissionId,
                packet(1, boss.userId()), null)), HANDOVER_TARGET_INVALID);

        // 过期的 dispatchSeq 被拒
        as(salesA1, () -> handovers.submit(salesA1, commissionId, packet(1, engineer.userId()), null));
        assertError(() -> as(engineer, () -> handovers.receive(engineer, commissionId,
                new DecisionCommand(null, false), null)), HANDOVER_DECISION_FORBIDDEN);
        as(engineer, () -> handovers.receive(engineer, commissionId, new DecisionCommand(null, true), null));
    }

    @Test
    void budgetIsRequiredAndProgressFollowsSubmissionVersion() {
        long customerId = as(salesA1, () -> captureCustomer(salesA1));
        assertError(() -> as(salesA1, () -> commissions.submit(salesA1, customerId, submit(TOTAL, FIRST), null)),
                SUBMISSION_BUDGET_REQUIRED);

        as(salesA1, () -> customers.saveScheme(salesA1, customerId,
                new SchemeCommand("BUDGET", "参考预算", "{}", new BigDecimal("13"), new BigDecimal("16"), 2,
                        new BigDecimal("400"), 68_000_000L), null));
        var detail = as(salesA1, () -> commissions.submit(salesA1, customerId, submit(TOTAL, FIRST), null));

        // 进度只接受当前提交版本
        assertError(() -> as(finance, () -> commissions.markFirstReady(detail.commission().getId(), 0, finance)),
                COMMISSION_STATE_INVALID);
        as(finance, () -> commissions.markFirstReady(detail.commission().getId(), 1, finance));
        // 已写入的进度重复调用是幂等的
        as(finance, () -> commissions.markFirstReady(detail.commission().getId(), 1, finance));
    }

    @Test
    void idempotencyKeyPreventsDuplicateSubmitAndReplayKeepsRevision() {
        long customerId = customerWithBudget();
        var first = as(salesA1, () -> commissions.submit(salesA1, customerId, submit(TOTAL, FIRST), "submit-1"));
        var replay = as(salesA1, () -> commissions.submit(salesA1, customerId, submit(TOTAL, FIRST), "submit-1"));

        assertThat(replay.commission().getId()).isEqualTo(first.commission().getId());
        assertThat(replay.submissions()).hasSize(1);
        assertThat(replay.commission().getRevision()).isEqualTo(first.commission().getRevision());
    }

    @Test
    void designAdminSeesNoAmountsAndOthersCannotReadCustomerPhone() {
        long customerId = customerWithBudget();
        as(boss, () -> policies.save(boss, new DesignPolicyService.PolicyCommand(TOTAL, FIRST, SCOPE, RECIPIENT), null));
        var detail = as(salesA1, () -> commissions.submit(salesA1, customerId, submit(TOTAL, FIRST), null));

        var hqView = as(designAdmin, () -> commissions.get(designAdmin, detail.commission().getId()));
        assertThat(hqView.submissions()).allSatisfy(s -> {
            assertThat(s.getTotalCents()).isNull();
            assertThat(s.getFirstCents()).isNull();
            assertThat(s.getRecipient()).isNull();
        });
        assertThat(hqView.displayStatus()).isEqualTo("设计中 · 首款办理中");
        // 工程负责人此刻看不到委托（尚未被指定为接收人）
        assertError(() -> as(engineer, () -> commissions.get(engineer, detail.commission().getId())), COMMISSION_NOT_FOUND);
    }

    @Test
    void commissionEntitiesAreTenantIgnored() {
        for (Class<?> entity : List.of(DesignPolicyDO.class, CommissionDO.class, CommissionSubmissionDO.class,
                CommissionProgressDO.class, CommissionHandoverDO.class)) {
            assertThat(entity.isAnnotationPresent(TenantIgnore.class)).as(entity.getSimpleName()).isTrue();
        }
    }

    // ===================== 准备与工具 =====================

    @BeforeEach
    void organization() {
        store = fixture.store();
        salesA1 = fixture.post(store.orgId(), SALES, store.salesTeamA());
        boss = fixture.post(store.orgId(), BOSS, null);
        engineer = fixture.post(store.orgId(), ENGINEER, store.engineeringTeam());
        finance = fixture.post(store.orgId(), FINANCE, null);
        designAdmin = fixture.post(fixture.headquarters(), DESIGN_ADMIN, null);
        Store other = fixture.store();
        otherStoreSales = fixture.post(other.orgId(), SALES, other.salesTeamA());
    }

    private long customerWithBudget() {
        long customerId = as(salesA1, () -> captureCustomer(salesA1));
        as(salesA1, () -> customers.saveScheme(salesA1, customerId,
                new SchemeCommand("BUDGET", "参考预算", "{\"kind\":\"budget\"}", new BigDecimal("13"),
                        new BigDecimal("16"), 2, new BigDecimal("400"), 68_000_000L), null));
        return customerId;
    }

    private long captureCustomer(PostSnapshot post) {
        CaptureResult result = leads.capture(post, new CaptureCommand("陈先生", phone(),
                "两层乡墅，按标准费用委托设计", "视频号咨询"), null);
        return result.customerId();
    }

    private static SubmitCommand submit(BigDecimal total, BigDecimal first) {
        return new SubmitCommand("两层乡墅，面宽13m进深16m", SCOPE, null, total, first, RECIPIENT,
                null, null, null, true);
    }

    private static DispatchCommand dispatch() {
        return new DispatchCommand("方案、施工图、预算书已交付", "总部设计行政", true);
    }

    private static PacketCommand packet(int dispatchSeq, long managerUserId) {
        return new PacketCommand(dispatchSeq, "{\"name\":\"成果文件\"}", "{\"name\":\"施工图\"}",
                "{\"name\":\"审核依据\"}", "{\"name\":\"客户确认\"}", "S2-2026", LocalDate.now(),
                "已向工程负责人现场交底", managerUserId, true);
    }

    private static String phone() {
        return String.valueOf(PHONES.incrementAndGet());
    }

    private <T> T as(PostSnapshot post, java.util.function.Supplier<T> action) {
        currentPost.use(post);
        try {
            return action.get();
        } finally {
            currentPost.clear();
        }
    }

    private void as(PostSnapshot post, Runnable action) {
        currentPost.use(post);
        try {
            action.run();
        } finally {
            currentPost.clear();
        }
    }

}
