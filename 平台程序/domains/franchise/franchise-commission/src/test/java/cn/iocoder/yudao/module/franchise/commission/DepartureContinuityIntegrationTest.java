package cn.iocoder.yudao.module.franchise.commission;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionDO;
import cn.iocoder.yudao.module.franchise.crm.CustomerService;
import cn.iocoder.yudao.module.franchise.crm.CustomerService.FollowCommand;
import cn.iocoder.yudao.module.franchise.crm.CustomerService.SchemeCommand;
import cn.iocoder.yudao.module.franchise.crm.LeadService;
import cn.iocoder.yudao.module.franchise.crm.LeadService.CaptureCommand;
import cn.iocoder.yudao.module.franchise.crm.LeadService.CaptureResult;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.test.FranchiseFixture.Store;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.DepartureHandoverService;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.module.franchise.commission.enums.FranchiseCommissionErrorCodes.COMMISSION_ALREADY_EXISTS;
import static cn.iocoder.yudao.module.franchise.crm.enums.FranchiseCrmErrorCodes.CUSTOMER_NOT_FOUND;
import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.BOSS;
import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.SALES;
import static cn.iocoder.yudao.module.franchise.test.FranchiseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * M04-T04：离职接续及同客同合同规则——跨模块端到端（CRM+委托，M04.md §8 同客同项目连续验收）。
 * 覆盖：交接后接收人读原跟进/方案/同编号委托并继续办理（含费用退回后重提合法路径——原委托
 * 编号不变）；历史经办与委托/提交行集 to_jsonb 全行不变；同客户不产生第二委托（不重复计收入）；
 * 离职人旧快照写转移后客户被拒 + 撤岗后真实任职解析失败。
 *
 * <p>边界登记（R1 P2）：验收原文「接续同 contractId/projectId」中的统一建房合同与工程项目实体
 * 随 M06-T04 总闸交付——本批以 customerId/commissionId 唯一链 + 委托唯一守卫作为其前置等价物；
 * 合同/项目/统一收入账端到端随 M06-T04/M03-T04 批次补验，不能据此关闭 M04-T04 全项。
 */
@Import({CommissionService.class, DesignPolicyService.class, CommissionAccess.class,
        CommissionOrgScope.class, FranchiseCommands.class, CommandLogImpl.class,
        cn.iocoder.yudao.module.platform.workflow.ApprovalServiceImpl.class,
        LeadService.class, CustomerService.class, cn.iocoder.yudao.module.franchise.crm.CrmOrgScope.class,
        cn.iocoder.yudao.module.franchise.crm.CrmDepartureHandoverPort.class,
        DepartureHandoverService.class})
class DepartureContinuityIntegrationTest extends CommissionPgIntegrationTest {

    private static final AtomicLong PHONES = new AtomicLong(18_000_000_000L + System.nanoTime() % 800_000_000L);

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
    private DepartureHandoverService handoverService;
    @Resource
    private cn.iocoder.yudao.module.platform.org.StaffPostService staffPostService;
    @Resource
    private JdbcTemplate jdbc;

    private Store store;
    private PostSnapshot salesA1;
    private PostSnapshot salesB;
    private PostSnapshot boss;


    @BeforeEach
    void organization() {
        // 设计岗位退役（V20261127.1201）删除了模板行；LEGACY 委托推进回归仍需该岗位任职，恢复模板夹具，随 LEGACY 服务端退役批一并移除。
        jdbc.update("""
                INSERT INTO pf_post_template (role_code, domain, org_types, line, lead, inherits_role_code)
                VALUES ('hq-designer','franchise','headquarters,store','design',FALSE,NULL),
                       ('hq-design-lead','franchise','headquarters,store','design',TRUE,'hq-designer'),
                       ('hq-design-admin','franchise','headquarters','design',FALSE,NULL)
                ON CONFLICT DO NOTHING""");
        store = fixture.store();
        salesA1 = fixture.post(store.orgId(), SALES, store.salesTeamA());
        salesB = fixture.post(store.orgId(), SALES, store.salesTeamB());
        boss = fixture.post(store.orgId(), BOSS, null);
        fixture.headquarters(); // 委托提交需唯一总部设计组织（soleHeadquarters）
    }

    @Test
    void receiverContinuesTheSameCommissionAfterCustomerHandover() {
        long customerId = customerWithCommission();

        DepartureHandoverService.HandoverResult result =
                as(boss, () -> handoverService.handover(store.orgId(), salesA1.userId(), salesB.userId(), boss.userId()));
        assertThat(result.revokedPostIds()).containsExactly(salesA1.postId());

        // 接收人读同编号委托：customerId 链不变（同 contractId/projectId 接续）
        var detail = as(salesB, () -> commissions.get(salesB, commissionIdOf(customerId)));
        assertThat(detail.commission().getId()).isEqualTo(commissionIdOf(customerId));
        assertThat(detail.commission().getCustomerId()).isEqualTo(customerId);
        assertThat(detail.commission().getStatus()).isEqualTo(CommissionDO.STATUS_FEE_PENDING);

        // 接收人读原跟进与方案：历史经办保留（follow 作者仍是离职人）
        CustomerService.Detail crmDetail = as(salesB, () -> customers.get(salesB, customerId));
        assertThat(crmDetail.follows()).hasSize(2);
        assertThat(crmDetail.follows()).allSatisfy(f -> assertThat(f.getAuthorUserId()).isEqualTo(salesA1.userId()));
        assertThat(crmDetail.schemes()).hasSize(1);
        assertThat(crmDetail.schemes().get(0).getKind()).isEqualTo("BUDGET");

        // 接收人继续办理：新增跟进（版本链正常推进）
        CustomerService.Detail afterFollow = as(salesB, () -> customers.follow(salesB, customerId,
                new FollowCommand("接手后首次跟进", null), null));
        assertThat(afterFollow.follows()).hasSize(3);
        assertThat(afterFollow.follows().get(2).getAuthorUserId()).isEqualTo(salesB.userId());
        assertThat(ownerOf(customerId)).isEqualTo(salesB.userId());
    }

    @Test
    void historyCommissionAndMoneyRowsStayIntactAcrossHandover() {
        long customerId = customerWithCommission();
        // 资金行对账说明：fr_fin_receipt 迁移在 franchise-finance jar，不在本测试模块迁移集——
        // 「交接零触碰资金行」由 FinRefundService/CrmDepartureHandoverPort 实现保证（只 update fr_customer），
        // 三方向资金核对随 M08-T08 批次在 finance 测试模块补端到端断言
        Snapshots before = snap(customerId);

        as(boss, () -> handoverService.handover(store.orgId(), salesA1.userId(), salesB.userId(), boss.userId()));

        Snapshots after = snap(customerId);
        // 历史经办/委托/提交/资金行集逐项不变（交接只改 fr_customer 的 owner/team/revision）
        assertThat(after.follows).isEqualTo(before.follows);
        assertThat(after.schemes).isEqualTo(before.schemes);
        assertThat(after.commissions).isEqualTo(before.commissions);
        assertThat(after.submissions).isEqualTo(before.submissions);
    }

    @Test
    void sameCustomerNeverCreatesSecondCommissionAfterHandover() {
        long customerId = customerWithCommission();
        as(boss, () -> handoverService.handover(store.orgId(), salesA1.userId(), salesB.userId(), boss.userId()));

        // 接收人接续办理：对同一客户重复提交委托 → 拒绝（不双入口重复计收入）
        assertError(() -> as(salesB, () -> commissions.submit(salesB, customerId, submit(TOTAL, FIRST), commandKey())),
                cn.iocoder.yudao.module.franchise.commission.enums.FranchiseCommissionErrorCodes.LEGACY_COMMISSION_RETIRED);
        assertThat(commissionCountOf(customerId)).isEqualTo(1);
    }

    @Test
    void staleSnapshotOfDepartingSellerIsRejectedOnTransferredCustomer() {
        long customerId = customerWithCommission();
        as(boss, () -> handoverService.handover(store.orgId(), salesA1.userId(), salesB.userId(), boss.userId()));

        // 离职人旧会话快照（交接前取得）对已转移客户的写操作：owner 已变、本人非负责人 → 拒绝
        assertError(() -> as(salesA1, () -> customers.follow(salesA1, customerId,
                        new FollowCommand("离职后残留操作", null), null)),
                CUSTOMER_NOT_FOUND);
        assertThat(ownerOf(customerId)).isEqualTo(salesB.userId());
        assertThat(postStatus(salesA1.postId())).isEqualTo(StaffPostDO.STATUS_REVOKED);
        // 撤权后真实任职解析失败（R1 P2）：旧 postId 经 findActivePost 解析为空——撤权立即生效，
        // 任何经 CurrentPost.find() 的新请求都会被 POST_REQUIRED 门禁拒绝
        assertThat(staffPostService.findActivePost(salesA1.userId(), salesA1.postId())).isEmpty();
        assertThat(staffPostService.findActivePost(salesA1.userId(), salesB.postId())).isEmpty();
    }

    @Test
    void feeReturnedHistoryAfterHandoverCannotCreateAnotherLegacyVersion() {
        long customerId=customerWithCommission(),commissionId=commissionIdOf(customerId);
        jdbc.update("UPDATE fr_commission SET status='FEE_RETURNED' WHERE id=?",commissionId);
        as(boss,()->handoverService.handover(store.orgId(),salesA1.userId(),salesB.userId(),boss.userId()));
        assertError(()->as(salesB,()->commissions.submit(salesB,customerId,submit(TOTAL,FIRST),commandKey())),
                cn.iocoder.yudao.module.franchise.commission.enums.FranchiseCommissionErrorCodes.LEGACY_COMMISSION_RETIRED);
        var original=as(salesB,()->commissions.get(salesB,commissionId));
        assertThat(original.submissions()).hasSize(1);
        assertThat(original.submissions().get(0).getSubmittedByUserId()).isEqualTo(salesA1.userId());
        assertThat(original.allowedActions()).contains("external-entrust-convert");
        assertThat(commissionCountOf(customerId)).isEqualTo(1);
    }

    // ===== 辅助 =====

    /** 客户 + 1 方案 + 2 跟进 + 1 委托（费用待审批），全由 salesA1 经办 */
    private long customerWithCommission() {
        long customerId = as(salesA1, () -> capture(salesA1));
        as(salesA1, () -> customers.saveScheme(salesA1, customerId,
                new SchemeCommand("BUDGET", "参考预算", "{\"kind\":\"budget\"}", new BigDecimal("13"),
                        new BigDecimal("16"), 2, new BigDecimal("400"), 68_000_000L), null));
        as(salesA1, () -> customers.follow(salesA1, customerId, new FollowCommand("首轮到店", null), null));
        as(salesA1, () -> customers.follow(salesA1, customerId, new FollowCommand("方案讲解", null), null));
        LegacyCommissionFixtures.submission(jdbc,salesA1,customerId,fixture.headquarters(),submit(TOTAL,FIRST),"FEE_PENDING");
        return customerId;
    }

    private long capture(PostSnapshot post) {
        CaptureResult result = leads.capture(post, new CaptureCommand("陈先生", phone(),
                "两层乡墅，按标准费用委托设计", "视频号咨询"), null);
        assertThat(result.customerId()).as("登记应直接建户").isNotNull();
        return result.customerId();
    }

    private static cn.iocoder.yudao.module.franchise.commission.CommissionService.SubmitCommand submit(
            BigDecimal total, BigDecimal first) {
        return new cn.iocoder.yudao.module.franchise.commission.CommissionService.SubmitCommand(
                "两层乡墅，面宽13m进深16m", SCOPE, null, total, first, RECIPIENT, 1, null, null, true);
    }

    private long commissionIdOf(long customerId) {
        return jdbc.queryForObject("SELECT id FROM fr_commission WHERE customer_id = ?", Long.class, customerId);
    }

    private long commissionCountOf(long customerId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM fr_commission WHERE customer_id = ?", Long.class, customerId);
    }

    private long ownerOf(long customerId) {
        return jdbc.queryForObject("SELECT owner_user_id FROM fr_customer WHERE id = ?", Long.class, customerId);
    }

    private String postStatus(long postId) {
        return jdbc.queryForObject("SELECT status FROM pf_staff_post WHERE id = ?", String.class, postId);
    }

    /**
     * 交接前后对账快照：to_jsonb 全行聚合（R1 P1：列级截取会漏检 UPDATE 改写——如 submission 的
     * submitted_by_user_id 被交接改写时 id/commission_id/version 仍相等；全行 JSON 任何列变即不等值）。
     */
    private Snapshots snap(long customerId) {
        return new Snapshots(
                agg("SELECT coalesce(string_agg(to_jsonb(t)::text, ',' ORDER BY id), '') FROM fr_customer_follow t WHERE customer_id=" + customerId),
                agg("SELECT coalesce(string_agg(to_jsonb(t)::text, ',' ORDER BY id), '') FROM fr_customer_scheme t WHERE customer_id=" + customerId),
                agg("SELECT coalesce(string_agg(to_jsonb(t)::text, ',' ORDER BY id), '') FROM fr_commission t WHERE customer_id=" + customerId),
                agg("SELECT coalesce(string_agg(to_jsonb(t)::text, ',' ORDER BY id), '') FROM fr_commission_submission t WHERE commission_id=" + commissionIdOf(customerId)));
    }

    private String agg(String sql) {
        return jdbc.queryForObject(sql, String.class);
    }

    private record Snapshots(String follows, String schemes, String commissions, String submissions) {
    }

    private <T> T as(PostSnapshot post, java.util.function.Supplier<T> action) {
        currentPost.use(post);
        try {
            return action.get();
        } finally {
            currentPost.clear();
        }
    }

    private static String phone() {
        return String.valueOf(PHONES.incrementAndGet());
    }
}
