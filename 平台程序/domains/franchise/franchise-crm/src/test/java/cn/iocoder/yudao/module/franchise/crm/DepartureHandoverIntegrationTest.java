package cn.iocoder.yudao.module.franchise.crm;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.crm.CustomerService.FollowCommand;
import cn.iocoder.yudao.module.franchise.crm.LeadService.CaptureCommand;
import cn.iocoder.yudao.module.franchise.crm.LeadService.CaptureResult;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.test.FranchiseFixture.Store;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.DepartureHandoverService;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.BOSS;
import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.FINANCE;
import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.SALES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M01-T04：离职交接全真实链路（franchise-crm 域）——CrmDepartureHandoverPort + DepartureHandoverService
 * + 真实组织/任职/客户表。覆盖：在途扫描、批量转移换主+随接收人团队、历史经办（跟进流水作者）保留、
 * 逐行 revision 推进（R1 P2-2）、接收岗位无团队时 team_id 置空（R1 P2-3）、域级接收资格
 * （R1 P1-1：财务岗不得接收客户）、重复交接零转移零重复、紧急撤权不转移只报待分配且 OPEN 行落地
 * 可补交接（R1 P1-3）、委托/工程/财务域未接入如实报 NOT_INTEGRATED。交接以 boss 岗位上下文执行
 * （控制器 requireScope 的操作者语义，OrgScopeRule 数据权限按当前岗位生效）。
 */
@Import({LeadService.class, CustomerService.class, CrmDepartureHandoverPort.class,
        DepartureHandoverService.class, CrmOrgScope.class, FranchiseCommands.class, CommandLogImpl.class})
class DepartureHandoverIntegrationTest extends FranchisePgIntegrationTest {

    private static final AtomicLong PHONES = new AtomicLong(17_000_000_000L + System.nanoTime() % 900_000_000L);

    @Resource
    private LeadService leads;
    @Resource
    private CustomerService customers;
    @Resource
    private DepartureHandoverService handoverService;
    @Resource
    private JdbcTemplate jdbc;

    private Store store;
    private PostSnapshot salesA1;
    private PostSnapshot salesB;
    private PostSnapshot boss;

    @BeforeEach
    void organization() {
        store = fixture.store();
        salesA1 = fixture.post(store.orgId(), SALES, store.salesTeamA());
        salesB = fixture.post(store.orgId(), SALES, store.salesTeamB());
        boss = fixture.post(store.orgId(), BOSS, null);
    }

    @Test
    void normalHandoverMovesInFlightCustomersToReceiverAndKeepsHistory() {
        long c1 = capture(salesA1, phone());
        long c2 = capture(salesA1, phone());
        long c3 = capture(salesA1, phone());
        as(salesA1, () -> customers.follow(salesA1, c1, new FollowCommand("首轮到店跟进", null), null));
        long revisionBefore = revisionOf(c1); // follow 自身也推进版本：基线取在交接动作前

        DepartureHandoverService.HandoverResult result =
                as(boss, () -> handoverService.handover(store.orgId(), salesA1.userId(), salesB.userId(), boss.userId()));

        // 清单：客户域已交接（3 名客户全部转移）
        assertThat(result.mode()).isEqualTo(DepartureHandoverService.MODE_NORMAL);
        assertThat(result.revokedPostIds()).containsExactly(salesA1.postId());
        assertThat(result.items()).contains(
                new DepartureHandoverService.HandoverItem("franchise-crm", "客户", 3, 3, DepartureHandoverService.ST_HANDED_OVER));
        // 未接入域如实报待分配，不伪造已交接
        assertThat(result.items()).containsSubsequence(
                new DepartureHandoverService.HandoverItem("franchise-commission", "设计委托", 0, 0, DepartureHandoverService.ST_NOT_INTEGRATED),
                new DepartureHandoverService.HandoverItem("franchise-engineering", "工程任务", 0, 0, DepartureHandoverService.ST_NOT_INTEGRATED),
                new DepartureHandoverService.HandoverItem("franchise-finance", "财务账款", 0, 0, DepartureHandoverService.ST_NOT_INTEGRATED));

        // 客户换主且随接收人团队；逐行 revision 推进（R1 P2-2：旧版本并发写被拒）
        assertThat(ownerOf(c1)).isEqualTo(salesB.userId());
        assertThat(ownerOf(c2)).isEqualTo(salesB.userId());
        assertThat(ownerOf(c3)).isEqualTo(salesB.userId());
        assertThat(teamOf(c1)).isEqualTo(store.salesTeamB());
        assertThat(revisionOf(c1)).isEqualTo(revisionBefore + 1);
        // 历史经办保留：跟进流水的作者仍是原业务员
        assertThat(jdbc.queryForObject(
                "SELECT author_user_id FROM fr_customer_follow WHERE customer_id = ?", Long.class, c1))
                .isEqualTo(salesA1.userId());
        // 撤权生效：原业务员岗位撤销
        assertThat(postStatus(salesA1.postId())).isEqualTo(StaffPostDO.STATUS_REVOKED);
    }

    @Test
    void receiverWithoutSalesLinePostIsRejectedAndNothingMoves() {
        long c1 = capture(salesA1, phone());
        // 财务岗在本组织在职：通用资格通过，但无客户承接资格（R1 P1-1）
        PostSnapshot financePost = fixture.post(store.orgId(), FINANCE, null);

        assertThatThrownBy(() -> asVoid(boss, () ->
                handoverService.handover(store.orgId(), salesA1.userId(), financePost.userId(), boss.userId())))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(PlatformOrgErrorCodes.HANDOVER_TARGET_ROLE_MISMATCH.getCode());
        assertThat(ownerOf(c1)).isEqualTo(salesA1.userId());
        assertThat(postStatus(salesA1.postId())).isEqualTo(StaffPostDO.STATUS_ACTIVE);
    }

    @Test
    void receiverPostWithoutTeamClearsTeamIdInsteadOfKeepingOldTeam() {
        long c1 = capture(salesA1, phone());
        // 接收岗位无团队（R1 P2-3：team_id 须写 NULL，不得保留原团队导致旧主管仍可见）
        PostSnapshot teamlessSales = fixture.post(store.orgId(), SALES, null);

        as(boss, () -> handoverService.handover(store.orgId(), salesA1.userId(), teamlessSales.userId(), boss.userId()));

        assertThat(ownerOf(c1)).isEqualTo(teamlessSales.userId());
        assertThat(jdbc.queryForObject("SELECT team_id FROM fr_customer WHERE id = ?", Long.class, c1)).isNull();
    }

    @Test
    void repeatedHandoverAfterCompletionTransfersNothingNew() {
        long c1 = capture(salesA1, phone());
        as(boss, () -> handoverService.handover(store.orgId(), salesA1.userId(), salesB.userId(), boss.userId()));
        assertThat(ownerOf(c1)).isEqualTo(salesB.userId());

        // 任职已撤且无待分配行：再次发起交接被拒，业务对象不可能被二次转移
        long from = salesA1.userId();
        long to = salesB.userId();
        long orgId = store.orgId();
        assertThatThrownBy(() -> asVoid(boss, () -> handoverService.handover(orgId, from, to, boss.userId())))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(PlatformOrgErrorCodes.HANDOVER_SOURCE_NO_POST.getCode());
        assertThat(ownerOf(c1)).isEqualTo(salesB.userId());
    }

    @Test
    void emergencyRevokeReportsPendingPersistsOpenRowAndSupportsBackfill() {
        long c1 = capture(salesA1, phone());

        DepartureHandoverService.HandoverResult result =
                as(boss, () -> handoverService.emergencyRevoke(store.orgId(), salesA1.userId(), boss.userId()));

        assertThat(result.mode()).isEqualTo(DepartureHandoverService.MODE_EMERGENCY);
        assertThat(result.toUserId()).isNull();
        assertThat(result.revokedPostIds()).containsExactly(salesA1.postId());
        assertThat(postStatus(salesA1.postId())).isEqualTo(StaffPostDO.STATUS_REVOKED);
        // 在途客户不转移：如实报待分配
        assertThat(result.items()).contains(
                new DepartureHandoverService.HandoverItem("franchise-crm", "客户", 1, 0, DepartureHandoverService.ST_PENDING_UNASSIGNED));
        assertThat(ownerOf(c1)).isEqualTo(salesA1.userId());
        // 待分配落地（R1 P1-3）：OPEN 行可查询
        assertThat(handoverService.listPending(store.orgId())).hasSize(1);

        // 补交接：源已撤岗但 OPEN 行在 → 放行；完成后客户转移 + OPEN 核销
        DepartureHandoverService.HandoverResult backfill =
                as(boss, () -> handoverService.handover(store.orgId(), salesA1.userId(), salesB.userId(), boss.userId()));
        assertThat(backfill.items()).contains(
                new DepartureHandoverService.HandoverItem("franchise-crm", "客户", 1, 1, DepartureHandoverService.ST_HANDED_OVER));
        assertThat(ownerOf(c1)).isEqualTo(salesB.userId());
        assertThat(handoverService.listPending(store.orgId())).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM pf_handover_pending WHERE org_id=? AND from_user_id=?",
                String.class, store.orgId(), salesA1.userId())).isEqualTo("HANDED_OVER");
    }

    @Test
    void receiverValidationBlocksDisabledAndCrossOrgTargets() {
        long c1 = capture(salesA1, phone());
        long from = salesA1.userId();
        long to = salesB.userId();
        long orgId = store.orgId();
        // 接收人在他组织（别家门店业务员）：拒绝
        Store other = fixture.store();
        PostSnapshot otherSales = fixture.post(other.orgId(), SALES, other.salesTeamA());
        assertThatThrownBy(() -> asVoid(boss, () -> handoverService.handover(orgId, from, otherSales.userId(), boss.userId())))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(PlatformOrgErrorCodes.HANDOVER_TARGET_NOT_IN_ORG.getCode());
        // 接收人停用：拒绝（校验先于转移）
        cn.iocoder.yudao.module.franchise.test.FakeSystemDirectory.INSTANCE.setUserActive(to, false);
        assertThatThrownBy(() -> asVoid(boss, () -> handoverService.handover(orgId, from, to, boss.userId())))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(PlatformOrgErrorCodes.HANDOVER_TARGET_DISABLED.getCode());
        // 客户原封不动（未发生任何转移）、任职未被撤
        assertThat(ownerOf(c1)).isEqualTo(salesA1.userId());
        assertThat(postStatus(salesA1.postId())).isEqualTo(StaffPostDO.STATUS_ACTIVE);
    }

    // ===== 查询与辅助 =====

    private long ownerOf(long customerId) {
        return jdbc.queryForObject("SELECT owner_user_id FROM fr_customer WHERE id = ?", Long.class, customerId);
    }

    private Long teamOf(long customerId) {
        return jdbc.queryForObject("SELECT team_id FROM fr_customer WHERE id = ?", Long.class, customerId);
    }

    private long revisionOf(long customerId) {
        return jdbc.queryForObject("SELECT revision FROM fr_customer WHERE id = ?", Long.class, customerId);
    }

    private String postStatus(long postId) {
        return jdbc.queryForObject("SELECT status FROM pf_staff_post WHERE id = ?", String.class, postId);
    }

    private long capture(PostSnapshot post, String phone) {
        CaptureResult result = as(post, () -> leads.capture(post,
                new CaptureCommand("陈先生", phone, "两层乡墅，先了解方案和费用", "视频号咨询"), null));
        assertThat(result.customerId()).as("登记应直接建户").isNotNull();
        return result.customerId();
    }

    private <T> T as(PostSnapshot post, java.util.function.Supplier<T> action) {
        currentPost.use(post);
        try {
            return action.get();
        } finally {
            currentPost.clear();
        }
    }

    private void asVoid(PostSnapshot post, Runnable action) {
        currentPost.use(post);
        try {
            action.run();
        } finally {
            currentPost.clear();
        }
    }

    private static String phone() {
        return String.valueOf(PHONES.incrementAndGet());
    }
}
