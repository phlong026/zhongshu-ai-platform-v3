package cn.iocoder.yudao.module.franchise.commission;

import cn.iocoder.yudao.module.franchise.commission.CommissionService.CompleteCommand;
import cn.iocoder.yudao.module.franchise.commission.CommissionService.DispatchCommand;
import cn.iocoder.yudao.module.franchise.commission.CommissionService.FeeDecisionCommand;
import cn.iocoder.yudao.module.franchise.commission.CommissionService.SubmitCommand;
import cn.iocoder.yudao.module.franchise.commission.HandoverService.DecisionCommand;
import cn.iocoder.yudao.module.franchise.commission.HandoverService.PacketCommand;
import cn.iocoder.yudao.module.franchise.crm.CustomerService.FollowCommand;
import cn.iocoder.yudao.module.franchise.crm.CustomerService.SchemeCommand;
import cn.iocoder.yudao.module.franchise.crm.CustomerService.TransferCommand;
import cn.iocoder.yudao.module.franchise.crm.LeadService.CaptureCommand;
import cn.iocoder.yudao.module.franchise.crm.LeadService.CaptureOutcome;
import cn.iocoder.yudao.module.franchise.crm.LeadService.CaptureResult;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.test.FranchiseFixture.Store;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.workflow.ApprovalServiceImpl;
import cn.iocoder.yudao.module.platform.workflow.TodoItem;
import cn.iocoder.yudao.module.platform.workflow.TodoServiceImpl;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.*;
import static org.assertj.core.api.Assertions.assertThat;

@Import({CommissionService.class, HandoverService.class, DesignPolicyService.class, CommissionAccess.class,
        cn.iocoder.yudao.module.franchise.engineering.content.EngineeringPortImpl.class,
        cn.iocoder.yudao.module.franchise.engineering.content.EngProjectService.class,
        cn.iocoder.yudao.module.franchise.sales.SaleContractQueryAdapter.class,
        CommissionOrgScope.class, FranchiseCommands.class, CommandLogImpl.class, ApprovalServiceImpl.class,
        FranchiseTodoProvider.class, TodoServiceImpl.class,
        cn.iocoder.yudao.module.franchise.crm.CustomerService.class,
        cn.iocoder.yudao.module.franchise.crm.LeadService.class, cn.iocoder.yudao.module.franchise.crm.CrmOrgScope.class})
class FranchiseTodoProviderTest extends CommissionPgIntegrationTest {

    private static final BigDecimal TOTAL = new BigDecimal("680000");
    private static final BigDecimal FIRST = new BigDecimal("408000");

    @Resource
    private cn.iocoder.yudao.module.franchise.crm.LeadService leads;
    @Resource
    private cn.iocoder.yudao.module.franchise.crm.CustomerService customers;
    @Resource
    private CommissionService commissions;
    @Resource
    private HandoverService handovers;
    @Resource
    private DesignPolicyService policies;
    @Resource
    private FranchiseTodoProvider todoProvider;
    @Resource
    private TodoServiceImpl todoService;
    @Resource
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private Store store;
    private PostSnapshot salesA1;
    private PostSnapshot salesB;
    private PostSnapshot boss;
    private PostSnapshot engineer;
    private PostSnapshot finance;
    private PostSnapshot designAdmin;

    @BeforeEach
    void organization() {
        store = fixture.store();
        salesA1 = fixture.post(store.orgId(), SALES, store.salesTeamA());
        salesB = fixture.post(store.orgId(), SALES, store.salesTeamB());
        boss = fixture.post(store.orgId(), BOSS, null);
        engineer = fixture.post(store.orgId(), ENGINEER, store.engineeringTeam());
        finance = fixture.post(store.orgId(), FINANCE, null);
        designAdmin = fixture.post(fixture.headquarters(), DESIGN_ADMIN, null);
    }

    @Test
    void salesSeesCustomerTodosUntilEachIsHandled() {
        CaptureResult captured = capture(salesA1);
        long customerId = captured.customerId();

        assertThat(todos(salesA1)).extracting(TodoItem::key)
                .contains("franchise:first-follow:" + customerId);

        // 保存预算后出现「待提交设计委托」，提交后委托待办取代客户待办
        as(salesA1, () -> customers.saveScheme(salesA1, customerId, budget(), null));
        assertThat(todos(salesA1)).extracting(TodoItem::key)
                .contains("franchise:submit-commission:" + customerId);
        assertThat(todos(salesA1)).filteredOn(t -> t.key().equals("franchise:submit-commission:" + customerId))
                .singleElement().satisfies(t -> assertThat(t.appPath()).isEqualTo(
                        "/pages/workflow/commission/index?view=submit&customerId=" + customerId));

        as(boss, () -> policies.save(boss,
                new DesignPolicyService.PolicyCommand(TOTAL, FIRST, "方案、施工图与交底", "总部财务"), null));
        var detail = as(salesA1, () -> commissions.submit(salesA1, customerId, submit(), commandKey()));

        // 标准费用路径直接进入设计；设计 §10：DESIGNING 对业务侧无待办（办理中）。
        // 客户仍是 REGISTERED，首次跟进待办保留（跟进与委托互不阻塞）
        assertThat(todos(salesA1)).extracting(TodoItem::key)
                .contains("franchise:first-follow:" + customerId)
                .doesNotContain("franchise:submit-commission:" + customerId,
                        "franchise:fee-pending:" + detail.commission().getId());
    }

    @Test
    void followupReminderOpensItsCustomerOnceAndMovesToTheNewOwner() {
        long id = capture(salesA1).customerId();
        // 客户跟进一律进八步页的当前步骤
        var path = "/pages/customer-flow/index?customerId=" + id;
        var today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var visitKey = "franchise:visit:" + id + ":" + today;
        assertThat(todos(salesA1)).filteredOn(t -> t.key().equals("franchise:first-follow:" + id))
                .singleElement().satisfies(t -> {
                    assertThat(t.appPath()).isEqualTo(path);
                    assertThat(t.title()).isEqualTo("「陈先生」待首次跟进");
                });
        var reminder = new FollowCommand("已联系，按约再访", today);
        String key = commandKey();
        var detail = as(salesA1, () -> customers.follow(salesA1, id, reminder, key));
        as(salesA1, () -> customers.follow(salesA1, id, reminder, key));
        assertThat(todos(salesA1)).filteredOn(t -> t.key().equals(visitKey))
                .singleElement().satisfies(t -> {
                    assertThat(t.appPath()).isEqualTo(path);
                    assertThat(t.title()).isEqualTo("「陈先生」约定跟进已到期");
                });
        assertThat(todos(salesA1)).extracting(TodoItem::key).doesNotContain("franchise:first-follow:" + id);
        as(boss, () -> customers.transfer(boss, id, new TransferCommand(
                salesB.userId(), "由新负责人继续回访"), detail.customer().getRevision(), commandKey()));
        assertThat(todos(salesA1)).extracting(TodoItem::key).doesNotContain(visitKey);
        assertThat(todos(salesB)).filteredOn(t -> t.key().equals(visitKey))
                .singleElement().satisfies(t -> assertThat(t.appPath()).isEqualTo(path));
        as(salesB, () -> customers.follow(salesB, id,
                new FollowCommand("本次回访已完成", null), commandKey()));
        assertThat(todos(salesB)).extracting(TodoItem::key).doesNotContain(visitKey);
    }

    @Test
    void leadIsRemindedAboutStaleCustomersAndLostCustomersThatWereConsultedAgain() {
        var lead = fixture.post(store.orgId(), SALES_LEAD, store.salesTeamA());
        long fresh = capture(salesA1).customerId();
        long untouched = capture(salesA1).customerId();
        long dormant = capture(salesA1).customerId();
        var today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        as(salesA1, () -> customers.follow(salesA1, dormant, new FollowCommand("电话已沟通", null), commandKey()));
        jdbc.update("UPDATE fr_customer SET create_time=create_time-interval '3 days' WHERE id=?", untouched);
        jdbc.update("UPDATE fr_customer SET last_follow_at=now()-interval '16 days' WHERE id=?", dormant);

        // 超期提醒只给业务负责人，不给业务员本人，也不给只看不办的老板
        assertThat(todos(lead)).filteredOn(t -> t.key().startsWith("franchise:stale:")).extracting(TodoItem::key)
                .containsExactlyInAnyOrder("franchise:stale:" + untouched, "franchise:stale:" + dormant);
        assertThat(todos(lead)).filteredOn(t -> t.key().equals("franchise:stale:" + untouched)).singleElement().satisfies(t -> {
            assertThat(t.title()).isEqualTo("「陈先生」登记 2 天仍未首次跟进");
            assertThat(t.appPath()).isEqualTo("/pages/customer-flow/index?customerId=" + untouched);
        });
        assertThat(todos(lead)).filteredOn(t -> t.key().equals("franchise:stale:" + dormant)).singleElement()
                .satisfies(t -> assertThat(t.title()).isEqualTo("「陈先生」超过 15 天没有跟进记录"));
        assertThat(todos(salesA1)).extracting(TodoItem::key).noneMatch(key -> key.startsWith("franchise:stale:"));
        assertThat(todos(boss)).extracting(TodoItem::key).noneMatch(key -> key.startsWith("franchise:stale:"));
        assertThat(todos(lead)).filteredOn(t -> t.key().equals("franchise:first-follow:" + fresh)).isNotEmpty();

        // 约了下次跟进日期的不再算超期，改由到期待办提醒
        as(salesA1, () -> customers.follow(salesA1, dormant, new FollowCommand("约好下周再访", today.plusDays(7)), commandKey()));
        assertThat(todos(lead)).extracting(TodoItem::key).doesNotContain("franchise:stale:" + dormant);

        // 流失客户再次有客资登记：负责人收到“待确认是否恢复”，登记时间清掉后待办消失
        long lost = capture(salesA1).customerId();
        jdbc.update("INSERT INTO fr_customer_workflow(id,tenant_id,company_id,customer_id,status,reconsulted_at) VALUES(?,?,?,?,'LOST',now())",
                System.nanoTime(), store.orgId(), store.orgId(), lost);
        assertThat(todos(salesA1)).filteredOn(t -> t.key().equals("franchise:reconsult:" + lost)).singleElement().satisfies(t -> {
            assertThat(t.title()).isEqualTo("「陈先生」流失客户再次咨询，待确认是否恢复");
            assertThat(t.appPath()).isEqualTo("/pages/workflow/index?view=customer&id=" + lost + "&tab=1");
        });
        jdbc.update("UPDATE fr_customer_workflow SET reconsulted_at=NULL WHERE customer_id=?", lost);
        assertThat(todos(salesA1)).extracting(TodoItem::key).doesNotContain("franchise:reconsult:" + lost);
    }

    @Test
    void appointmentIsDueAtShanghaiMidnightEvenIfTheHostUsesUtc() {
        long id = capture(salesA1).customerId();
        var due = LocalDate.of(2035, 1, 2);
        as(salesA1, () -> customers.follow(salesA1, id,
                new FollowCommand("跨时区的预约", due), commandKey()));
        var midnight = Instant.parse("2035-01-01T16:00:00Z");
        var shanghai = ZoneId.of("Asia/Shanghai");
        var hostClock = Clock.fixed(midnight, ZoneOffset.UTC);
        var storeClock = hostClock.withZone(shanghai);
        try (var clocks = Mockito.mockStatic(Clock.class, Mockito.CALLS_REAL_METHODS)) {
            clocks.when(Clock::systemDefaultZone).thenReturn(hostClock);
            clocks.when(() -> Clock.system(shanghai)).thenReturn(storeClock);
            assertThat(todos(salesA1)).filteredOn(t -> t.key().equals("franchise:visit:" + id + ":" + due))
                    .singleElement().satisfies(t -> assertThat(t.since().toInstant()).isEqualTo(midnight));
        }
    }

    @Test
    void bossApprovesFeeWithAmountAndDesignAdminTakesOver() {
        long customerId = customerWithBudget();
        var detail = as(salesA1, () -> commissions.submit(salesA1, customerId,
                new SubmitCommand("两层乡墅", "方案", null, new BigDecimal("750000"),
                        new BigDecimal("400000"), "总部财务", 1, null, null, true), commandKey()));
        long id = detail.commission().getId();

        // 老板看到带金额的可办理审批；设计行政看不到费用审批
        List<TodoItem> bossTodos = todos(boss);
        assertThat(bossTodos).filteredOn(t -> t.key().equals("franchise:fee-approve:" + id))
                .singleElement().satisfies(t -> {
                    assertThat(t.actionable()).isTrue();
                    assertThat(t.amountCents()).isEqualTo(75_000_000L);
                    assertThat(t.appPath()).isEqualTo(path("progress", id));
                });
        assertTodoPath(salesA1, "fee-pending:" + id, "progress", id, false);
        assertThat(todos(designAdmin)).extracting(TodoItem::key).doesNotContain("franchise:fee-approve:" + id);

        as(boss, () -> commissions.feeDecide(boss, id, new FeeDecisionCommand(true, "同意"), commandKey()));
        as(finance, () -> seedFirst(id, 1, finance));

        // 设计行政：首款就绪 → 确认设计完成
        assertThat(todos(designAdmin)).extracting(TodoItem::key).contains("franchise:design-complete:" + id + ":1");
        assertTodoPath(designAdmin, "design-complete:" + id + ":1", "complete", id, true);
        as(designAdmin, () -> commissions.complete(designAdmin, id, new CompleteCommand("完成", true), commandKey()));
        assertTodoPath(salesA1, "tail-follow:" + id, "progress", id, true);
        as(finance, () -> seedTailPaid(id, 1, finance));
        as(finance, () -> seedTailReady(id, 1, finance));

        // 设计行政：尾款就绪 → 下发成果；业务侧同步出现跟进尾款/移交待办
        assertThat(todos(designAdmin)).extracting(TodoItem::key).contains("franchise:design-dispatch:" + id + ":1");
        assertTodoPath(designAdmin, "design-dispatch:" + id + ":1", "dispatch", id, true);
        as(designAdmin, () -> commissions.dispatch(designAdmin, id,
                new DispatchCommand("成果交付", "总部设计行政", true,"TEST-V1",101L,102L,103L), commandKey()));
        assertThat(todos(salesA1)).extracting(TodoItem::key).contains("franchise:handover-prepare:" + id);
        assertTodoPath(salesA1, "handover-prepare:" + id, "handover", id, true);
    }

    @Test
    void handoverShowsReviewForManagerOnly() {
        long id = commissionDispatched();
        as(salesA1, () -> handovers.submit(salesA1, id, packet(engineer.userId()), commandKey()));

        assertThat(todos(engineer)).extracting(TodoItem::key)
                .contains("franchise:handover-review:" + id + ":1");
        assertThat(todos(salesA1)).extracting(TodoItem::key)
                .contains("franchise:handover-waiting:" + id + ":1");
        assertTodoPath(engineer, "handover-review:" + id + ":1", "receive", id, true);
        assertTodoPath(salesA1, "handover-waiting:" + id + ":1", "progress", id, false);
        PostSnapshot otherEngineer = fixture.post(store.orgId(), ENGINEER, store.engineeringTeam());
        assertThat(todos(otherEngineer)).extracting(TodoItem::key)
                .doesNotContain("franchise:handover-review:" + id + ":1");

        var engineerTodos = todos(engineer);
        assertThat(engineerTodos.stream().filter(t -> t.key().startsWith("franchise:handover-review"))
                .allMatch(TodoItem::actionable)).isTrue();

        as(engineer, () -> handovers.receive(engineer, id, new DecisionCommand(null, true), commandKey()));
        assertThat(todos(engineer)).extracting(TodoItem::key)
                .doesNotContain("franchise:handover-review:" + id + ":1");
    }

    @Test
    void returnedFeesAndPacketsKeepRealCommissionIdAndWritableView() {
        long customerId = customerWithBudget();
        long feeId = as(salesA1, () -> commissions.submit(salesA1, customerId,
                new SubmitCommand("两层乡墅", "方案", null, new BigDecimal("750000"),
                        new BigDecimal("400000"), "总部财务", 1, null, null, true), commandKey()))
                .commission().getId();
        assertThat(feeId).isNotEqualTo(customerId);
        as(boss, () -> commissions.feeDecide(boss, feeId, new FeeDecisionCommand(false, "核对范围"), commandKey()));
        assertTodoPath(salesA1, "fee-returned:" + feeId + ":1", "submit", feeId, true);

        long packetId = commissionDispatched();
        as(salesA1, () -> handovers.submit(salesA1, packetId, packet(engineer.userId()), commandKey()));
        as(engineer, () -> handovers.returnPacket(engineer, packetId,
                new DecisionCommand("补充交底", true), commandKey()));
        assertTodoPath(salesA1, "handover-returned:" + packetId + ":1", "handover", packetId, true);
    }

    @Test
    void pendingLeadsShowAsActionableOnlyForManagersAndBoss() {
        String phone = String.valueOf(15_800_000_000L + System.nanoTime() % 700_000_000L);
        as(salesA1, () -> leads.capture(salesA1,
                new CaptureCommand("陈先生", phone, "两层乡墅", "视频号"), null));
        CaptureResult pending = as(salesB, () -> leads.capture(salesB,
                new CaptureCommand("陈先生", phone, "再次咨询", "视频号"), null));
        assertThat(pending.outcome()).isEqualTo(CaptureOutcome.LINK_REQUIRED);

        // 登记人（业务二组）只能看到等待中；一组负责人与老板可办理
        assertThat(todos(salesB)).filteredOn(t -> t.key().startsWith("franchise:lead-link"))
                .singleElement().satisfies(t -> assertThat(t.actionable()).isFalse());
        PostSnapshot leadWholeLine = fixture.post(store.orgId(), SALES_LEAD, null);
        assertThat(todos(leadWholeLine)).filteredOn(t -> t.key().startsWith("franchise:lead-link"))
                .singleElement().satisfies(TodoItem::actionable);
        assertThat(todos(boss)).filteredOn(t -> t.key().startsWith("franchise:lead-link"))
                .singleElement().satisfies(TodoItem::actionable);
    }

    @Test
    void todoServiceSortsActionableFirstAndDeduplicates() {
        long id = commissionDispatched();
        as(salesA1, () -> handovers.submit(salesA1, id, packet(engineer.userId()), commandKey()));

        var list = as(engineer, () -> todoService.list(engineer));
        assertThat(list.items()).isNotEmpty();
        assertThat(list.actionableCount()).isPositive();
        // 可办理的排在不可办理的前面
        boolean seenInactive = false;
        for (TodoItem item : list.items()) {
            if (!item.actionable()) {
                seenInactive = true;
            } else {
                assertThat(seenInactive).as("actionable 待办应排在前面: " + item.key()).isFalse();
            }
        }
        assertThat(list.failedDomains()).isEmpty();
    }

    // ===================== 工具 =====================

    /** 走标准费用路径把委托推到已下发 */
    private long commissionDispatched() {
        long customerId = customerWithBudget();
        as(boss, () -> policies.save(boss,
                new DesignPolicyService.PolicyCommand(TOTAL, FIRST, "方案、施工图与交底", "总部财务"), null));
        var detail = as(salesA1, () -> commissions.submit(salesA1, customerId, submit(), commandKey()));
        long id = detail.commission().getId();
        as(finance, () -> seedFirst(id, 1, finance));
        as(designAdmin, () -> commissions.complete(designAdmin, id, new CompleteCommand("完成", true), commandKey()));
        as(finance, () -> seedTailPaid(id, 1, finance));
        as(finance, () -> seedTailReady(id, 1, finance));
        as(designAdmin, () -> commissions.dispatch(designAdmin, id,
                new DispatchCommand("成果交付", "总部设计行政", true,"TEST-V1",101L,102L,103L), commandKey()));
        return id;
    }

    private long customerWithBudget() {
        CaptureResult captured = capture(salesA1);
        as(salesA1, () -> customers.saveScheme(salesA1, captured.customerId(), budget(), null));
        return captured.customerId();
    }

    private CaptureResult capture(PostSnapshot post) {
        String phone = String.valueOf(15_800_000_000L + System.nanoTime() % 700_000_000L);
        return as(post, () -> leads.capture(post,
                new CaptureCommand("陈先生", phone, "两层乡墅，按标准费用委托设计", "视频号咨询"), null));
    }

    private static SchemeCommand budget() {
        return new SchemeCommand("BUDGET", "参考预算", "{}", new BigDecimal("13"), new BigDecimal("16"),
                2, new BigDecimal("400"), 68_000_000L);
    }

    private static SubmitCommand submit() {
        return new SubmitCommand("两层乡墅", "方案、施工图与交底", null, TOTAL, FIRST, "总部财务",
                1, null, null, true);
    }

    private static PacketCommand packet(long managerUserId) {
        return new PacketCommand(1,null,null,null,null,null,LocalDate.now(),
                "已向工程负责人现场交底",managerUserId,true,104L);
    }

    private List<TodoItem> todos(PostSnapshot post) {
        return as(post, () -> todoProvider.list(post));
    }

    private void assertTodoPath(PostSnapshot post, String key, String view, long commissionId, boolean actionable) {
        assertThat(todos(post)).filteredOn(t -> t.key().equals("franchise:" + key))
                .singleElement().satisfies(t -> {
                    assertThat(t.appPath()).isEqualTo(path(view, commissionId));
                    assertThat(t.actionable()).isEqualTo(actionable);
                });
    }

    private static String path(String view, long commissionId) {
        return "/pages/workflow/commission/index?view=" + view + "&commissionId=" + commissionId;
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
