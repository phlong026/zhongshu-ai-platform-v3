package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.finance.dal.FinAbnormalFundDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinAbnormalFundMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.test.FranchiseFixture;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT;
import static cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes.*;
import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.FINANCE;
import static cn.iocoder.yudao.module.franchise.test.FranchiseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M08-T08 三方向退款冲减与异常资金登记（Testcontainers PG，批次#33）：
 * 1. client 方向（回归锁定）：可退余额递减、超退拒绝、作废回补；
 * 2. expense 方向（本批修复后）：仅已核实付款可退回（作废并发由行锁挡）、上限=该付款单金额
 *    （退多付不减正确费用——超退拒绝不形成新欠款）、流水号进防重表；
 * 3. 更正链 CAS：同一被作废记录至多一个有效替代，败者整笔回滚；
 * 4. 同键并发重复落单（R1 P1）：同幂等键并发登记至多落单一行（聚合一致校验/唯一索引双保险）；
 * 5. 来源互斥 CHECK（R1 P2）：client 行禁携 payment_id、expense 行禁携 receipt_id；
 * 6. 异常资金：独立留痕不进有效账（FinProjectAccountService 口径不含）、日期待核待归月、
 *    转正关联 CAS、目标合法性、作废幂等。
 */
@Import({FinRefundService.class, FinReceiptService.class, FinPaymentService.class, FinAbnormalFundService.class,
        FinProjectAccountService.class, FranchiseCommands.class, CommandLogImpl.class,
        FinRefundThreeDirectionIntegrationTest.Config.class})
class FinRefundThreeDirectionIntegrationTest extends FranchisePgIntegrationTest {

    @TestConfiguration
    static class Config {
        /** FinReceiptService 的三个跨域端口：本测试只走退款/异常资金路径，不触达收款登记——代理桩即早退保险 */
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

    private static final AtomicLong SEQ = new AtomicLong(9_100_000_000L + System.nanoTime() % 700_000_000L);

    @Resource
    private FinRefundService refundService;
    @Resource
    private FinPaymentService paymentService;
    @Resource
    private FranchiseCommands commands;
    @Resource
    private FinAbnormalFundService abnormalFundService;
    @Resource
    private FinProjectAccountService accountService;
    @Resource
    private FinAbnormalFundMapper abnormalFundMapper;
    @Resource
    private JdbcTemplate jdbc;

    private FranchiseFixture.Store store;
    private PostSnapshot financeA;

    @BeforeEach
    void organization() {
        store = fixture.store();
        financeA = fixture.post(store.orgId(), FINANCE, null);
    }

    // ========== client 方向（既有路径回归） ==========

    @Test
    void clientRefundDecreasesBalanceAndVoidRestoresIt() {
        long receiptId = newReceipt(1_000_000L, FinReceiptService.STATUS_REGISTERED, null);
        long refundId = as(financeA, () -> refundService.register(financeA, idem(),
                new FinRefundService.RegisterCommand(receiptId, 400_000L, "客户协商退款", LocalDate.of(2026, 11, 2),
                        null, null, null)));
        assertThat(activeRefunded(receiptId)).isEqualTo(400_000L);
        assertThat(refundDirection(refundId)).isEqualTo("client");

        assertThatThrownBy(() -> as(financeA, () -> refundService.register(financeA, idem(),
                new FinRefundService.RegisterCommand(receiptId, 700_000L, "超退", LocalDate.of(2026, 11, 2),
                        null, null, null))))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(REFUND_EXCEED_BALANCE.getCode()));

        asRun(financeA, () -> refundService.void_(financeA, refundId, "误录撤销"));
        assertThat(activeRefunded(receiptId)).isZero(); // 余额随状态回补
    }

    @Test
    void correctionRejectsForeignTxnOwnersAndRollsBackThenCanRetry() {
        long receipt = newReceipt(1_000_000L, FinReceiptService.STATUS_REGISTERED, null);
        long old = as(financeA, () -> refundService.register(financeA, idem(),
                new FinRefundService.RegisterCommand(receipt, 100L, "old", LocalDate.of(2026,11,2), "ORIGINAL", null, null)));
        asRun(financeA, () -> refundService.void_(financeA, old, "correct"));
        for (String type : List.of("receipt", "refund", "expense-refund")) {
            long foreignId = "refund".equals(type) ? old + 999 : old;
            String txn = "FOREIGN-" + type;
            jdbc.update("INSERT INTO fr_fin_txn_no(id,company_id,txn_no,owner_type,owner_id,closed) VALUES(?,?,?,?,?,false)",
                    SEQ.incrementAndGet(), store.orgId(), txn, type, foreignId);
            String key = idem();
            var cmd = new FinRefundService.RegisterCommand(receipt,100L,"correct",LocalDate.of(2026,11,2),txn,null,old);
            assertError(() -> as(financeA, () -> refundService.register(financeA,key,cmd)), TXN_NO_OCCUPIED);
            assertThat(jdbc.queryForObject("SELECT owner_id FROM fr_fin_txn_no WHERE company_id=? AND txn_no=?",Long.class,store.orgId(),txn)).isEqualTo(foreignId);
            assertThat(jdbc.queryForObject("SELECT replaced_by_id FROM fr_fin_refund WHERE id=?",Long.class,old)).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_refund WHERE receipt_id=?",Long.class,receipt)).isEqualTo(1);
        }
        var cmd = new FinRefundService.RegisterCommand(receipt,100L,"correct",LocalDate.of(2026,11,2),"ORIGINAL",null,old);
        String key = idem();
        long replacement = as(financeA, () -> refundService.register(financeA,key,cmd));
        assertThat(as(financeA, () -> refundService.register(financeA,key,cmd))).isEqualTo(replacement);
        assertThat(jdbc.queryForObject("SELECT owner_id FROM fr_fin_txn_no WHERE company_id=? AND txn_no='ORIGINAL'",Long.class,store.orgId())).isEqualTo(replacement);
    }

    @Test
    void concurrentSameNumberCorrectionsHaveOneWinnerAndNoOrphans() throws Exception {
        long receipt = newReceipt(1_000_000L, FinReceiptService.STATUS_REGISTERED, null);
        long old = as(financeA, () -> refundService.register(financeA, idem(),
                new FinRefundService.RegisterCommand(receipt,100L,"old",LocalDate.of(2026,11,2),"RACE",null,null)));
        asRun(financeA, () -> refundService.void_(financeA,old,"correct"));
        var gate = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var results = new ArrayList<Future<Boolean>>();
            for (int i=0;i<2;i++) results.add(pool.submit(() -> {
                gate.await();
                try { as(financeA, () -> refundService.register(financeA,idem(),
                    new FinRefundService.RegisterCommand(receipt,100L,"new",LocalDate.of(2026,11,2),"RACE",null,old))); return true; }
                catch (ServiceException e) { return false; }
            }));
            gate.countDown(); int winners=0;
            for(var result:results) if(result.get(10,java.util.concurrent.TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_refund WHERE receipt_id=?",Long.class,receipt)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT owner_id FROM fr_fin_txn_no WHERE company_id=? AND txn_no='RACE'",Long.class,store.orgId()))
                .isEqualTo(jdbc.queryForObject("SELECT replaced_by_id FROM fr_fin_refund WHERE id=?",Long.class,old));
        } finally { pool.shutdownNow(); }
    }

    // ========== expense 方向（批次#33 修复后语义） ==========

    @Test
    void expenseRefundCapsAtPaymentAmountAndRejectsVoidedPayment() {
        long paymentId = newPayment(500_000L);
        long first = as(financeA, () -> refundService.registerExpense(financeA, idem(),
                expense(paymentId, 300_000L, null)));
        assertThat(activeExpenseRefunded(paymentId)).isEqualTo(300_000L);
        assertThat(refundDirection(first)).isEqualTo("expense");

        // 退多付不减正确费用：Σ退回+本次 > 付款单金额 → 拒绝，不形成新欠款
        assertError(() -> as(financeA, () -> refundService.registerExpense(financeA, idem(),
                expense(paymentId, 250_000L, null))), EXPENSE_REFUND_EXCEED);

        // 流水号进 fr_fin_txn_no 防重表（R1 P2）：首用登记成功，同号再登记拒绝
        long withTxn = as(financeA, () -> refundService.registerExpense(financeA, idem(),
                expense(paymentId, 100_000L, "TXN-EXP-001")));
        assertThat(withTxn).isPositive();
        assertError(() -> as(financeA, () -> refundService.registerExpense(financeA, idem(),
                expense(paymentId, 10_000L, "TXN-EXP-001"))), TXN_NO_OCCUPIED);

        // R2 P1-2：行锁内拒绝作废存在有效退回的付款（paymentService.void_ 走同一把锁+有效退回检查）
        assertError(() -> asRun(financeA, () -> paymentService.void_(financeA, paymentId, "试作废有退回的付款")),
                PAYMENT_HAS_ACTIVE_REFUND);
        // 付款作废后不可退回（R1 P2：状态复核）
        asRun(financeA, () -> refundService.void_(financeA, first, "清理退回后作废付款"));
        asRun(financeA, () -> refundService.void_(financeA, withTxn, "清理第二笔退回"));
        asRun(financeA, () -> paymentService.void_(financeA, paymentId, "退回已清理，可作废"));
        long payment2 = newPayment(100_000L);
        asRun(financeA, () -> paymentService.void_(financeA, payment2, "先作废付款"));
        assertError(() -> as(financeA, () -> refundService.registerExpense(financeA, idem(),
                expense(payment2, 100_000L, null))), EXPENSE_REFUND_SOURCE_INVALID);
    }

    // ========== expense 更正链（R2 P1-3：同付款单替代 CAS + 同号 owner 原子切换） ==========

    @Test
    void expenseCorrectionChainReclaimsTxnAndCapsReplacement() {
        long paymentId = newPayment(500_000L);
        long original = as(financeA, () -> refundService.registerExpense(financeA, idem(),
                expense(paymentId, 300_000L, "TXN-EXP-C1")));
        asRun(financeA, () -> refundService.void_(financeA, original, "金额录入错误"));

        // 同号更正：identity owner 原子切换，不释放流水号供任意复用
        FinRefundService.ExpenseRefundCommand corrected = new FinRefundService.ExpenseRefundCommand(
                paymentId, 200_000L, "更正为正确金额", LocalDate.of(2026, 11, 3), "TXN-EXP-C1", null, original);
        long correctedId = as(financeA, () -> refundService.registerExpense(financeA, idem(), corrected));
        assertThat(jdbc.queryForObject("SELECT replaced_by_id FROM fr_fin_refund WHERE id = ?",
                Long.class, original)).isEqualTo(correctedId);
        assertThat(jdbc.queryForObject("SELECT owner_id FROM fr_fin_txn_no WHERE txn_no = 'TXN-EXP-C1'",
                Long.class)).isEqualTo(correctedId);

        // 同一被作废记录至多一个替代：CAS 败者回滚
        FinRefundService.ExpenseRefundCommand loser = new FinRefundService.ExpenseRefundCommand(
                paymentId, 100_000L, "并发更正败者", LocalDate.of(2026, 11, 3), null, null, original);
        assertError(() -> as(financeA, () -> refundService.registerExpense(financeA, idem(), loser)),
                REFUND_ALREADY_REPLACED);
        // 跨付款单的替代上下文拒绝
        long otherPayment = newPayment(100_000L);
        asRun(financeA, () -> refundService.void_(financeA, correctedId, "腾出余额"));
        FinRefundService.ExpenseRefundCommand crossPayment = new FinRefundService.ExpenseRefundCommand(
                otherPayment, 100_000L, "跨付款单替代", LocalDate.of(2026, 11, 3), null, null, original);
        assertError(() -> as(financeA, () -> refundService.registerExpense(financeA, idem(), crossPayment)),
                REFUND_NOT_FOUND);
        assertThat(activeExpenseRefunded(paymentId)).isZero();
    }

    // ========== 更正链 CAS ==========

    @Test
    void correctionChainAllowsOnlyOneWinnerPerReplacedRefund() {
        long receiptId = newReceipt(1_000_000L, FinReceiptService.STATUS_REGISTERED, null);
        long original = as(financeA, () -> refundService.register(financeA, idem(),
                new FinRefundService.RegisterCommand(receiptId, 300_000L, "误录金额", LocalDate.of(2026, 11, 2),
                        null, null, null)));
        asRun(financeA, () -> refundService.void_(financeA, original, "金额录入错误"));

        long corrected = as(financeA, () -> refundService.register(financeA, idem(),
                new FinRefundService.RegisterCommand(receiptId, 200_000L, "更正为正确金额", LocalDate.of(2026, 11, 2),
                        null, null, original)));
        assertThat(jdbc.queryForObject("SELECT replaced_by_id FROM fr_fin_refund WHERE id = ?",
                Long.class, original)).isEqualTo(corrected);

        // CAS 争抢：同一被作废记录至多一个有效替代，败者整笔回滚
        assertError(() -> as(financeA, () -> refundService.register(financeA, idem(),
                new FinRefundService.RegisterCommand(receiptId, 100_000L, "并发更正败者", LocalDate.of(2026, 11, 2),
                        null, null, original))), REFUND_ALREADY_REPLACED);
        assertThat(activeRefunded(receiptId)).isEqualTo(200_000L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fr_fin_refund WHERE receipt_id = ? AND status = '已核实'",
                Long.class, receiptId)).isEqualTo(1L);
    }

    // ========== 同键并发重复落单（R1 P1 回归锁） ==========

    @Test
    void sameKeyConcurrentRegisterPersistsExactlyOneRefund() throws Exception {
        long receiptId = newReceipt(1_000_000L, FinReceiptService.STATUS_REGISTERED, null);
        String key = idem();
        FinRefundService.RegisterCommand cmd = new FinRefundService.RegisterCommand(
                receiptId, 100_000L, "并发同键", LocalDate.of(2026, 11, 2), null, null, null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        // R2 P2-7 两阶段 gate：强制两线程都确认「未重放」后再放行业务操作——确定性覆盖原缺陷窗口
        CyclicBarrier replayChecked = new CyclicBarrier(2);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                var replayed = commands.replayed(financeA, key, cmd);
                if (replayed.isPresent()) throw new IllegalStateException("预检不应命中重放");
                replayChecked.await(); // 两线程均读到未重放后同时放行
                try {
                    return as(financeA, () -> refundService.register(financeA, key, cmd));
                } catch (Exception e) {
                    return e; // 败者以异常回滚（STATE_VERSION_CONFLICT/IDEMPOTENCY_KEY_REUSED）
                }
            }));
        }
        start.countDown();
        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> f : futures) {
            outcomes.add(f.get());
        }
        pool.shutdown();
        List<Object> failures = outcomes.stream().filter(o -> o instanceof Throwable).toList();
        failures.forEach(f -> assertThat(f).isInstanceOf(ServiceException.class)
                .as("败者以业务异常回滚（非裸运行时异常）"));

        long rows = jdbc.queryForObject("SELECT COUNT(*) FROM fr_fin_refund WHERE receipt_id = ? AND reason = '并发同键'",
                Long.class, receiptId);
        assertThat(rows).as("同键并发至多落单一行").isEqualTo(1L);
        List<Long> ids = outcomes.stream()
                .filter(o -> o instanceof Long)
                .map(o -> (Long) o)
                .toList();
        assertThat(ids).isNotEmpty();
        assertThat(ids.stream().distinct().count()).as("成功方（含重放）返回同一退款 id").isEqualTo(1);
    }

    /** R2 P2-7：多轮并发——不变量（至多一行）逐轮成立，且冲突窗口至少可观察一次（存在失败方） */
    @Test
    void sameKeyConcurrentAcrossRoundsKeepsInvariantAndObservesLoser() throws Exception {
        long receiptId = newReceipt(100_000_000L, FinReceiptService.STATUS_REGISTERED, null);
        int observedFailures = 0;
        for (int round = 0; round < 12; round++) {
            String key = idem();
            FinRefundService.RegisterCommand cmd = new FinRefundService.RegisterCommand(
                    receiptId, 100_000L, "多轮并发", LocalDate.of(2026, 11, 2), null, null, null);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            CyclicBarrier replayChecked = new CyclicBarrier(2);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    commands.replayed(financeA, key, cmd);
                    replayChecked.await(); // 两线程均过预检后同时放行
                    try {
                        return as(financeA, () -> refundService.register(financeA, key, cmd));
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> f : futures) {
                outcomes.add(f.get());
            }
            pool.shutdown();
            long rows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM fr_fin_refund WHERE receipt_id = ? AND reason = '多轮并发'",
                    Long.class, receiptId);
            assertThat(rows).as("第" + round + "轮至多落单一行").isEqualTo(1L);
            // R3 P2-5：本轮若存在败者，必为 ServiceException 且错误码限定冲突两类（排除裸运行时异常假绿）
            for (Object o : outcomes) {
                if (o instanceof Throwable) {
                    assertThat(o).isInstanceOf(ServiceException.class);
                    long code = ((ServiceException) o).getCode();
                    long conflictCode = STATE_VERSION_CONFLICT.getCode();
                    long reusedCode = IDEMPOTENCY_KEY_REUSED.getCode();
                    assertThat(code).isIn(conflictCode, reusedCode); // long 局部变量统一装箱类型（Integer!=Long）
                    observedFailures += 1;
                }
            }
            jdbc.update("DELETE FROM fr_fin_refund WHERE receipt_id = ? AND reason = '多轮并发'", receiptId);
        }
        // R3 P2-4：多轮用例=不变量压测（逐轮至多一行+败者类型/错误码限定）；冲突分支的确定性覆盖
        // 由 commandsRecordRejectsForeignAggregateOnSameIdempotencyKey 单测承担（append 返回既有事件分支）
    }

    /** R3 P2-4：确定性覆盖原缺陷分支——同键已提交事件属他聚合时 record 必须拒绝（聚合一致性校验） */
    @Test
    void commandsRecordRejectsForeignAggregateOnSameIdempotencyKey() {
        String key = "key-agg-" + SEQ.incrementAndGet();
        commands.record(financeA, "fin-refund", 101L, 1, "fin-refund-register", java.util.Map.of("k", "v"), key);
        // 并发 B 已插自身业务行后 append 读到 A 已提交事件（payload 相同）→ 静默返回原事件——聚合校验必须抛冲突
        assertError(() -> commands.record(financeA, "fin-refund", 102L, 1, "fin-refund-register",
                java.util.Map.of("k", "v"), key), STATE_VERSION_CONFLICT);
        // 同键不同 payload → 键复用拒绝（append 直抛）
        assertError(() -> commands.record(financeA, "fin-refund", 102L, 1, "fin-refund-register",
                java.util.Map.of("k", "v2"), key), IDEMPOTENCY_KEY_REUSED);
    }

    // ========== 来源互斥 CHECK（R1 P2） ==========

    @Test
    void sourceCheckRejectsCrossDirectionRows() {
        long receiptId = newReceipt(100_000L, FinReceiptService.STATUS_REGISTERED, null);
        long paymentId = newPayment(100_000L);
        // client 行携带 payment_id → 违反互斥 CHECK
        assertThatThrownBy(() -> jdbc.update("INSERT INTO fr_fin_refund (id, receipt_id, company_id, amount_cents, "
                        + "status, reason, operator_user_id, refund_direction, payment_id, date) "
                        + "VALUES (?, ?, ?, 10000, '已核实', '违规组合', ?, 'client', ?, '2026-11-02')",
                SEQ.incrementAndGet(), receiptId, store.orgId(), financeA.userId(), paymentId))
                .isInstanceOf(DataIntegrityViolationException.class);
        // expense 行携带 receipt_id → 违反互斥 CHECK
        assertThatThrownBy(() -> jdbc.update("INSERT INTO fr_fin_refund (id, company_id, amount_cents, "
                        + "status, reason, operator_user_id, refund_direction, payment_id, receipt_id, date) "
                        + "VALUES (?, ?, 10000, '已核实', '违规组合', ?, 'expense', ?, ?, '2026-11-02')",
                SEQ.incrementAndGet(), store.orgId(), financeA.userId(), paymentId, receiptId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ========== 异常资金登记 ==========

    @Test
    void abnormalFundStaysOutOfEffectiveAccountAndLinksBySameFundFact() {
        long projectId = SEQ.incrementAndGet();
        long receiptId = newReceipt(600_000L, FinReceiptService.STATUS_REGISTERED, projectId);

        // 日期待核（business_date 空）→ 待归月单列；项目归属留真实已知信息
        long fundId = as(financeA, () -> abnormalFundService.register(financeA, idem(),
                new FinAbnormalFundService.RegisterCommand("客户收款", 600_000L, "王某",
                        projectId, null, "基础完成后", null, "bank", null, "原单缺失待补", null)));
        FinAbnormalFundDO fund = abnormalFundMapper.selectById(fundId);
        assertThat(fund.getStatus()).isEqualTo(FinAbnormalFundDO.STATUS_PENDING_LINK);
        assertThat(fund.getBusinessDate()).isNull();

        // 填了日期须合法（未来日期拒绝）；方向 null 直接业务拒绝（R2 P2-6 非 NPE）
        assertError(() -> as(financeA, () -> abnormalFundService.register(financeA, idem(),
                new FinAbnormalFundService.RegisterCommand("客户收款", 1000L, null, null, null, null,
                        LocalDate.of(2999, 1, 1), null, null, null, null))), BUSINESS_DATE_FUTURE);
        assertError(() -> as(financeA, () -> abnormalFundService.register(financeA, idem(),
                new FinAbnormalFundService.RegisterCommand(null, 1000L, null, null, null, null,
                        null, null, null, null, null))), ABNORMAL_DIRECTION_INVALID);

        // 不进有效账：项目有效实收只含 fr_fin_receipt，异常登记不凑平
        var account = as(financeA, () -> accountService.projectAccount(store.orgId(), projectId));
        assertThat(account.receivedConfirmed()).isEqualTo(600_000L);

        // 同一资金事实核对（R2 P1-4）：金额相等/项目归属一致/待登记不得关闭/目标存在/目标类型映射
        long mismatched = newReceipt(888_000L, FinReceiptService.STATUS_REGISTERED, projectId);
        assertError(() -> asRun(financeA, () -> abnormalFundService.link(financeA, fundId, "receipt", mismatched)),
                ABNORMAL_LINK_TARGET_INVALID); // 金额不等=不同一事实
        long wrongProjectReceipt = newReceipt(600_000L, FinReceiptService.STATUS_REGISTERED, SEQ.incrementAndGet());
        assertError(() -> asRun(financeA, () -> abnormalFundService.link(financeA, fundId, "receipt", wrongProjectReceipt)),
                ABNORMAL_LINK_TARGET_INVALID); // 项目归属不一致
        long pendingReceipt = newReceipt(600_000L, FinReceiptService.STATUS_PENDING, projectId);
        assertError(() -> asRun(financeA, () -> abnormalFundService.link(financeA, fundId, "receipt", pendingReceipt)),
                ABNORMAL_LINK_TARGET_INVALID); // 待登记不能关闭异常
        assertError(() -> asRun(financeA, () -> abnormalFundService.link(financeA, fundId, "receipt", 999_999_999L)),
                ABNORMAL_LINK_TARGET_INVALID); // 目标不存在
        long linkedPayment = newPayment(100_000L);
        assertError(() -> asRun(financeA, () -> abnormalFundService.link(financeA, fundId, "payment", linkedPayment)),
                ABNORMAL_LINK_TARGET_INVALID); // 跨类型：客户收款异常不能被付款单关闭
        // 已知流水号与目标不一致（目标收款无此流水）→ 拒绝
        long fundWithTxn = as(financeA, () -> abnormalFundService.register(financeA, idem(),
                new FinAbnormalFundService.RegisterCommand("客户收款", 600_000L, null,
                        projectId, null, null, null, "bank", "ABN-TXN-1", "带流水号异常", null)));
        assertError(() -> asRun(financeA, () -> abnormalFundService.link(financeA, fundWithTxn, "receipt", receiptId)),
                ABNORMAL_LINK_TARGET_INVALID);

        // 等额同项目同号 → 转正成功；CAS 至多一次；同一收款单至多解释一笔异常（目标防双占用）
        asRun(financeA, () -> abnormalFundService.link(financeA, fundId, "receipt", receiptId));
        assertError(() -> asRun(financeA, () -> abnormalFundService.link(financeA, fundId, "receipt", receiptId)),
                ABNORMAL_FUND_ALREADY_LINKED);
        assertThat(abnormalFundMapper.selectById(fundId).getStatus()).isEqualTo(FinAbnormalFundDO.STATUS_LINKED);
        long second = as(financeA, () -> abnormalFundService.register(financeA, idem(),
                new FinAbnormalFundService.RegisterCommand("客户收款", 600_000L, null,
                        projectId, null, null, null, "bank", "ABN-TXN-2", "第二笔待关联", null)));
        assertError(() -> asRun(financeA, () -> abnormalFundService.link(financeA, second, "receipt", receiptId)),
                ABNORMAL_LINK_TARGET_INVALID); // 流水号不一致拒（该笔带号）
        // R3 P2：占用负测去干扰——第三笔无流水号（流水号检查必过）→ 命中 requireNotOccupied 占用拒绝
        long third = as(financeA, () -> abnormalFundService.register(financeA, idem(),
                new FinAbnormalFundService.RegisterCommand("客户收款", 600_000L, null,
                        projectId, null, null, null, "bank", null, "第三笔无号", null)));
        assertError(() -> asRun(financeA, () -> abnormalFundService.link(financeA, third, "receipt", receiptId)),
                ABNORMAL_LINK_TARGET_INVALID);
        // R3 P1：DB 级兜底——已关联状态同目标直插违反 uk_fr_fin_abnormal_fund_link（并发双关联最后防线）
        assertThatThrownBy(() -> jdbc.update("INSERT INTO fr_fin_abnormal_fund (id, company_id, fund_direction, "
                + "amount_cents, status, linked_type, linked_id, operator_user_id) "
                + "VALUES (?, ?, '客户收款', 1000, '已关联', 'receipt', ?, ?)",
                SEQ.incrementAndGet(), store.orgId(), receiptId, financeA.userId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 作废幂等 + R2 P2-5：已作废行让出同号（部分唯一索引排除已作废）——作废 ABN-TXN-2 持有者后同号重登成功
        asRun(financeA, () -> abnormalFundService.void_(financeA, fundId, "事实误录"));
        asRun(financeA, () -> abnormalFundService.void_(financeA, fundId, "重复作废无二次效果"));
        assertThat(abnormalFundMapper.selectById(fundId).getStatus()).isEqualTo(FinAbnormalFundDO.STATUS_VOIDED);
        asRun(financeA, () -> abnormalFundService.void_(financeA, second, "让出流水号"));
        long reregistered = as(financeA, () -> abnormalFundService.register(financeA, idem(),
                new FinAbnormalFundService.RegisterCommand("客户收款", 600_000L, null,
                        projectId, null, null, null, "bank", "ABN-TXN-2", "作废后同号重登", null)));
        assertThat(reregistered).isPositive();
    }

    // ========== 项目账已付冲减（R2 复核补充：P1-1 修复锁定） ==========

    @Test
    void projectAccountPaidNetsExpenseRefunds() {
        long projectId = SEQ.incrementAndGet();
        long payableId = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO fr_fin_payable (id, company_id, project_id, total_cents, recipient, status) "
                + "VALUES (?, ?, ?, 400000, '供应商', '进行中')", payableId, store.orgId(), projectId);
        long paymentId = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO fr_fin_payment (id, payable_id, project_id, company_id, amount_cents, status, operator_user_id) "
                + "VALUES (?, ?, ?, ?, 400000, '已核实', ?)", paymentId, payableId, projectId, store.orgId(), financeA.userId());
        as(financeA, () -> refundService.registerExpense(financeA, idem(),
                expense(paymentId, 150_000L, null)));
        var account = as(financeA, () -> accountService.projectAccount(store.orgId(), projectId));
        assertThat(account.paidConfirmed()).as("项目已付=付款400000−支出退回150000").isEqualTo(250_000L);
    }

    // ========== 幂等重放==========

    @Test
    void replayedKeyReturnsOriginalRefundId() {
        long receiptId = newReceipt(100_000L, FinReceiptService.STATUS_REGISTERED, null);
        String key = idem();
        FinRefundService.RegisterCommand cmd = new FinRefundService.RegisterCommand(
                receiptId, 100_000L, "幂等重放", LocalDate.of(2026, 11, 2), null, null, null);
        long first = as(financeA, () -> refundService.register(financeA, key, cmd));
        long second = as(financeA, () -> refundService.register(financeA, key, cmd));
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fr_fin_refund WHERE receipt_id = ? AND reason = '幂等重放'",
                Long.class, receiptId)).isEqualTo(1L);
    }

    // ========== helpers ==========

    private long activeRefunded(long receiptId) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_cents),0) FROM fr_fin_refund WHERE receipt_id = ? AND status = '已核实'",
                Long.class, receiptId);
        return sum == null ? 0 : sum;
    }

    private long activeExpenseRefunded(long paymentId) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_cents),0) FROM fr_fin_refund WHERE payment_id = ? AND status = '已核实'",
                Long.class, paymentId);
        return sum == null ? 0 : sum;
    }

    private String refundDirection(long refundId) {
        return jdbc.queryForObject("SELECT refund_direction FROM fr_fin_refund WHERE id = ?", String.class, refundId);
    }

    private long newReceipt(long amountCents, String status, Long projectId) {
        long id = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO fr_fin_receipt (id, company_id, project_id, source_type, name, amount_cents, "
                        + "status, registrar_user_id, received_date) VALUES (?, ?, ?, 'manual', '测试收款', ?, ?, ?, '2026-11-01')",
                id, store.orgId(), projectId, amountCents, status, financeA.userId());
        return id;
    }

    private long newPayment(long amountCents) {
        long payableId = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO fr_fin_payable (id, company_id, total_cents, recipient, status) "
                        + "VALUES (?, ?, ?, '供应商', '进行中')",
                payableId, store.orgId(), amountCents + 1000L);
        long id = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO fr_fin_payment (id, payable_id, company_id, amount_cents, status, operator_user_id, pay_date) "
                        + "VALUES (?, ?, ?, ?, '已核实', ?, '2026-11-01')",
                id, payableId, store.orgId(), amountCents, financeA.userId());
        return id;
    }

    private FinRefundService.ExpenseRefundCommand expense(long paymentId, long amountCents, String txn) {
        return new FinRefundService.ExpenseRefundCommand(paymentId, amountCents, "供应商退回材料款",
                LocalDate.of(2026, 11, 3), txn, null, null);
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
