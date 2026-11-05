package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.module.franchise.finance.dal.FinAbnormalFundDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinAbnormalFundMapper;
import cn.iocoder.yudao.module.franchise.finance.todo.FinanceTodoProvider;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.test.FranchiseFixture;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.workflow.TodoItem;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes.RECONCILIATION_MONTH_INVALID;
import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.FINANCE;
import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.SALES;
import static cn.iocoder.yudao.module.franchise.test.FranchiseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * M08-T08 批次#34 对账视图（Testcontainers PG）：
 * 1. 四口径——毛额=Σ已登记收款（月内）；退款=Σ client 已核实未被替代（月内，expense 回流不计）；
 *    净额=毛−退；异常=已核待关联合计+笔数，其中日期待核子集单列（待归月不落月）。
 * 2. 月过滤边界：他月收款/退款不串月；日期待核（NULL）退款与异常不落任何月。
 * 3. 待办投影：已核待关联逐条 fr-fin:abnormal-link:*，route=对账视图页，作废后即消失；非 finance 岗不可见。
 */
@Import({FinReconciliationService.class, FinAbnormalFundService.class, FinanceTodoProvider.class,
        FranchiseCommands.class, CommandLogImpl.class,
        FinReconciliationIntegrationTest.Config.class})
class FinReconciliationIntegrationTest extends FranchisePgIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        cn.iocoder.yudao.module.franchise.api.CommissionQueryPort commissionQueryPort() {
            return untouchedStub(cn.iocoder.yudao.module.franchise.api.CommissionQueryPort.class);
        }

        @Bean
        cn.iocoder.yudao.module.franchise.api.AssetAccessPort assetAccessPort() {
            return untouchedStub(cn.iocoder.yudao.module.franchise.api.AssetAccessPort.class);
        }

        @Bean
        cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort storeServiceAccountPort() {
            return untouchedStub(cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort.class);
        }

        @SuppressWarnings("unchecked")
        private static <T> T untouchedStub(Class<T> type) {
            return (T) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (proxy, method, args) -> {
                        throw new UnsupportedOperationException("本测试不触达 " + type.getSimpleName() + "." + method.getName());
                    });
        }
    }

    private static final AtomicLong SEQ = new AtomicLong(9_300_000_000L + System.nanoTime() % 600_000_000L);

    @Resource
    private FinReconciliationService reconciliationService;
    @Resource
    private FinAbnormalFundService abnormalFundService;
    @Resource
    private FinanceTodoProvider todoProvider;
    @Resource
    private FinAbnormalFundMapper abnormalFundMapper;
    @Resource
    private JdbcTemplate jdbc;

    private FranchiseFixture.Store store;
    private PostSnapshot financeA;
    private PostSnapshot salesA;

    @BeforeEach
    void organization() {
        store = fixture.store();
        financeA = fixture.post(store.orgId(), FINANCE, null);
        salesA = fixture.post(store.orgId(), SALES, store.salesTeamA());
    }

    @Test
    void fourLedgersSplitGrossRefundNetAbnormal() {
        long companyId = store.orgId();
        newReceipt(1_000_000L, FinReceiptService.STATUS_REGISTERED, LocalDate.of(2026, 11, 2));
        newReceipt(500_000L, FinReceiptService.STATUS_REGISTERED, LocalDate.of(2026, 10, 28)); // 他月不串
        newReceipt(300_000L, FinReceiptService.STATUS_PENDING, LocalDate.of(2026, 11, 3)); // 待登记不计毛额
        newRefund(400_000L, "client", LocalDate.of(2026, 11, 4));
        newRefund(200_000L, "expense", LocalDate.of(2026, 11, 4)); // 支出回流不进收入对账
        newRefund(100_000L, "client", null); // 日期待核不落月

        var view = as(financeA, () -> reconciliationService.monthReconciliation(companyId, "2026-11"));
        assertThat(view.grossReceiptsCents()).as("毛额=月内已登记").isEqualTo(1_000_000L);
        assertThat(view.clientRefundsCents()).as("退款=月内 client 已核实").isEqualTo(400_000L);
        assertThat(view.netReceivedCents()).as("净额=毛−退").isEqualTo(600_000L);

        // 异常资金：一笔日期待核（待归月子集）、一笔已填日期——均不落月、全量单列
        as(financeA, () -> abnormalFundService.register(financeA, idem(),
                new FinAbnormalFundService.RegisterCommand("客户收款", 888_000L, "王某", null, null, null,
                        null, "bank", null, "原单缺失", null)));
        as(financeA, () -> abnormalFundService.register(financeA, idem(),
                new FinAbnormalFundService.RegisterCommand("客户收款", 600_000L, null, null, null, null,
                        LocalDate.of(2026, 11, 5), "bank", null, "超原单余额", null)));
        var withAbnormal = as(financeA, () -> reconciliationService.monthReconciliation(companyId, "2026-11"));
        assertThat(withAbnormal.abnormalPendingCents()).isEqualTo(1_488_000L);
        assertThat(withAbnormal.abnormalPendingCount()).isEqualTo(2);
        assertThat(withAbnormal.abnormalDatePendingCents()).as("待归月子集单列").isEqualTo(888_000L);
        assertThat(withAbnormal.abnormalDatePendingCount()).isEqualTo(1);
        // 异常不进毛额/净额（不凑平）
        assertThat(withAbnormal.grossReceiptsCents()).isEqualTo(1_000_000L);
        assertThat(withAbnormal.netReceivedCents()).isEqualTo(600_000L);
    }

    @Test
    void rejectsInvalidMonthFormat() {
        assertError(() -> as(financeA, () -> reconciliationService.monthReconciliation(store.orgId(), "2026-13")),
                RECONCILIATION_MONTH_INVALID);
        assertError(() -> as(financeA, () -> reconciliationService.monthReconciliation(store.orgId(), "abc")),
                RECONCILIATION_MONTH_INVALID);
    }

    @Test
    void todoProviderProjectsPendingAbnormalFundsOnlyForFinanceRoles() {
        // 无异常资金时 finance 岗也可能有到账待办——只断言 abnormal-link 子集
        long fundId = as(financeA, () -> abnormalFundService.register(financeA, idem(),
                new FinAbnormalFundService.RegisterCommand("客户退款", 250_000L, "李某", null, null, null,
                        null, "bank", null, "原收款单缺失", null)));
        var financeTodos = as(financeA, () -> todoProvider.list(financeA));
        TodoItem item = financeTodos.stream()
                .filter(t -> t.key().equals(FinanceTodoProvider.DOMAIN + ":abnormal-link:" + fundId))
                .findFirst().orElseThrow();
        assertThat(item.title()).contains("异常资金待关联").contains("客户退款").contains("日期待核");
        assertThat(item.amountCents()).isEqualTo(250_000L);
        assertThat(item.actionable()).isTrue();
        assertThat(item.appPath()).contains("/pages/admin/finance-desk/index").contains("tab=ledger");

        // 非 finance 岗不可见
        var salesTodos = as(salesA, () -> todoProvider.list(salesA));
        assertThat(salesTodos).isEmpty();

        // 作废后待办即消失（实时投影）
        asRun(financeA, () -> abnormalFundService.void_(financeA, fundId, "误录撤销"));
        var after = as(financeA, () -> todoProvider.list(financeA));
        assertThat(after.stream().noneMatch(t -> t.key().equals(FinanceTodoProvider.DOMAIN + ":abnormal-link:" + fundId)))
                .isTrue();
    }

    // ========== helpers ==========

    private void newReceipt(long amountCents, String status, LocalDate receivedDate) {
        jdbc.update("INSERT INTO fr_fin_receipt (id, company_id, source_type, name, amount_cents, status, "
                        + "registrar_user_id, received_date) VALUES (?, ?, 'manual', '对账测试收款', ?, ?, ?, ?)",
                SEQ.incrementAndGet(), store.orgId(), amountCents, status, financeA.userId(), receivedDate);
    }

    private void newRefund(long amountCents, String direction, LocalDate date) {
        Long receiptId = null;
        Long paymentId = null;
        if ("client".equals(direction)) {
            receiptId = SEQ.incrementAndGet();
            // 原收款落 10 月——不污染 11 月毛额口径
            jdbc.update("INSERT INTO fr_fin_receipt (id, company_id, source_type, name, amount_cents, status, "
                            + "registrar_user_id, received_date) VALUES (?, ?, 'manual', '对账退款原收款', ?, '已登记', ?, '2026-10-05')",
                    receiptId, store.orgId(), amountCents + 1000L, financeA.userId());
        } else {
            paymentId = newPaymentRow(amountCents + 1000L);
        }
        jdbc.update("INSERT INTO fr_fin_refund (id, receipt_id, payment_id, company_id, amount_cents, status, reason, "
                        + "operator_user_id, refund_direction, date) VALUES (?, ?, ?, ?, ?, '已核实', '对账测试退款', ?, ?, ?)",
                SEQ.incrementAndGet(), receiptId, paymentId, store.orgId(), amountCents, financeA.userId(), direction, date);
    }

    private long newPaymentRow(long amountCents) {
        long payableId = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO fr_fin_payable (id, company_id, total_cents, recipient, status) "
                + "VALUES (?, ?, ?, '供应商', '进行中')", payableId, store.orgId(), amountCents);
        long id = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO fr_fin_payment (id, payable_id, company_id, amount_cents, status, operator_user_id) "
                + "VALUES (?, ?, ?, ?, '已核实', ?)", id, payableId, store.orgId(), amountCents, financeA.userId());
        return id;
    }

    private <T> T as(PostSnapshot post, java.util.function.Supplier<T> action) {
        currentPost.use(post);
        try {
            return action.get();
        } finally {
            currentPost.clear();
        }
    }

    private void asRun(PostSnapshot post, Runnable action) {
        currentPost.use(post);
        try {
            action.run();
        } finally {
            currentPost.clear();
        }
    }

    private static String idem() {
        return "idem-" + SEQ.incrementAndGet();
    }
}
