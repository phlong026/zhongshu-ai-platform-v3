package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.franchise.finance.dal.FinAbnormalFundDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinAbnormalFundMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPayableDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPayableMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPaymentDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPaymentMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinRefundDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinRefundMapper;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.FINANCE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 批2：五类账表 selectPage 的真实 PG 回归（Testcontainers PG17 + 本域 Flyway 迁移）。
 * 覆盖逐表组合筛选、日期口径、组织隔离、分页 total/list，以及旧 selectByCompany 未分页语义不变。
 */
@org.springframework.context.annotation.Import({cn.iocoder.yudao.module.franchise.org.FranchiseCommands.class,cn.iocoder.yudao.module.platform.command.CommandLogImpl.class})
class FinancePageMapperIntegrationTest extends FranchisePgIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong(9_400_000_000L + System.nanoTime() % 500_000_000L);

    @Resource private FinReceiptMapper receiptMapper;
    @Resource private FinPayableMapper payableMapper;
    @Resource private FinPaymentMapper paymentMapper;
    @Resource private FinRefundMapper refundMapper;
    @Resource private FinAbnormalFundMapper abnormalFundMapper;
    @Resource private JdbcTemplate jdbc;

    private long orgId;
    private long userId;

    @BeforeEach
    void organization() {
        var store = fixture.store();
        orgId = store.orgId();
        var post = fixture.post(orgId, FINANCE, null);
        userId = post.userId();
        currentPost.use(post);
    }

    @Resource private cn.iocoder.yudao.module.franchise.org.FranchiseCommands commands;

    @Test
    void paymentConfirmationReturnsOnlyThisPostsCommittedCommand() {
        var post=currentPost.require();
        long payment=SEQ.incrementAndGet();
        var service=new FinPaymentService();
        org.springframework.test.util.ReflectionTestUtils.setField(service,"jdbc",jdbc);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"commands",commands);
        var key="payment-confirm-"+payment;
        assertThat(service.registrationResult(post,key)).isEmpty();
        commands.record(post,"fin-payment",payment,1,"fin-payment-register",java.util.Map.of("payableId",1),key);
        assertThat(service.registrationResult(post,key)).containsOnlyKeys("paymentId").containsEntry("paymentId",Long.toString(payment));
        var other=new cn.iocoder.yudao.module.platform.org.PostSnapshot(post.postId()+1,post.userId(),post.orgId(),post.orgType(),post.roleCode(),null,false,java.util.Set.of());
        assertThat(service.registrationResult(other,key)).isEmpty();
        assertThat(service.registrationResult(fixture.post(fixture.store().orgId(),FINANCE,null),key)).isEmpty();
    }

    private static PageParam page(int pageNo, int pageSize) {
        PageParam page = new PageParam();
        page.setPageNo(pageNo);
        page.setPageSize(pageSize);
        return page;
    }

    // ===== 实收：receivedDate 区间 + projectId + method + sourceType + keyword + 分页 =====

    @Test
    void receiptsPageCombinesFiltersAndPaginates() {
        long inA = newReceipt("已登记", LocalDate.of(2026, 11, 5), 101L, "bank", "manual", "核对收款甲", "TX-A");
        long inB = newReceipt("已登记", LocalDate.of(2026, 11, 8), 101L, "cash", "manual", "核对收款乙", "TX-B");
        newReceipt("待登记", LocalDate.of(2026, 11, 6), 101L, "bank", "manual", "核对收款丙", "TX-C"); // 状态不符
        newReceipt("已登记", LocalDate.of(2026, 10, 30), 101L, "bank", "manual", "核对收款丁", "TX-D"); // 期间外
        newReceipt("已登记", LocalDate.of(2026, 11, 6), 999L, "bank", "manual", "核对收款戊", "TX-E"); // 项目不符
        long inF = newReceipt("已登记", LocalDate.of(2026, 11, 6), 101L, "bank", "design", "核对收款己", "TX-F"); // 来源不符

        PageResult<FinReceiptDO> first = receiptMapper.selectPage(page(1, 1), orgId, "已登记",
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), 101L, "bank", "manual", null);
        assertThat(first.getTotal()).isEqualTo(1);
        assertThat(first.getList()).extracting(FinReceiptDO::getId).containsExactly(inA);

        // sourceType 逐参数筛选：design 只出 inF
        PageResult<FinReceiptDO> bySourceType = receiptMapper.selectPage(page(1, 10), orgId, "已登记",
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), 101L, null, "design", null);
        assertThat(bySourceType.getTotal()).isEqualTo(1);
        assertThat(bySourceType.getList()).extracting(FinReceiptDO::getId).containsExactly(inF);

        // 不筛 method/sourceType：inA / inB / inF 均符合其余组合条件
        PageResult<FinReceiptDO> all = receiptMapper.selectPage(page(1, 10), orgId, "已登记",
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), 101L, null, null, null);
        assertThat(all.getTotal()).isEqualTo(3);
        assertThat(all.getList()).extracting(FinReceiptDO::getId).containsExactlyInAnyOrder(inA, inB, inF);

        PageResult<FinReceiptDO> byKeyword = receiptMapper.selectPage(page(1, 10), orgId, null,
                null, null, null, null, null, "TX-B");
        assertThat(byKeyword.getTotal()).isEqualTo(1);
        assertThat(byKeyword.getList().get(0).getId()).isEqualTo(inB);

        // 第 2 页为空（total=1，pageSize=1）
        PageResult<FinReceiptDO> second = receiptMapper.selectPage(page(2, 1), orgId, "已登记",
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), 101L, "bank", "manual", null);
        assertThat(second.getList()).isEmpty();
        assertThat(second.getTotal()).isEqualTo(1);
    }

    @Test
    void receiptsPageIsolatesCompany() {
        newReceipt("已登记", LocalDate.of(2026, 11, 5), 101L, "bank", "manual", "本公司", "TX-ORG-A");
        long otherOrg = fixture.store().orgId();
        newReceiptFor(otherOrg, "已登记", LocalDate.of(2026, 11, 5), 101L, "bank", "manual", "他公司", "TX-ORG-B");

        PageResult<FinReceiptDO> result = receiptMapper.selectPage(page(1, 10), orgId, null, null, null, null, null, null, null);
        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getList()).extracting(FinReceiptDO::getName).containsExactly("本公司");
    }

    // ===== 付款：payDate 区间 + projectId + keyword =====

    @Test
    void paymentsPageFiltersByPayDateProjectAndKeyword() {
        long inA = newPayment("已核实", LocalDate.of(2026, 11, 3), 201L, "TX-P-A");
        newPayment("已核实", LocalDate.of(2026, 11, 4), 999L, "TX-P-B"); // 项目不符
        newPayment("已核实", LocalDate.of(2026, 10, 20), 201L, "TX-P-C"); // 期间外
        newPayment("已作废", LocalDate.of(2026, 11, 3), 201L, "TX-P-D"); // 状态不符

        PageResult<FinPaymentDO> result = paymentMapper.selectPage(page(1, 10), orgId, "已核实",
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), 201L, null);
        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getList()).extracting(FinPaymentDO::getId).containsExactly(inA);

        PageResult<FinPaymentDO> byKeyword = paymentMapper.selectPage(page(1, 10), orgId, null,
                null, null, null, "TX-P-C");
        assertThat(byKeyword.getTotal()).isEqualTo(1);
    }

    // ===== 退款：date 区间 + receiptId + refundDirection + status =====

    @Test
    void refundsPageFiltersByDateReceiptIdDirectionAndStatus() {
        long receiptA = SEQ.incrementAndGet();
        long receiptB = SEQ.incrementAndGet();
        long paymentA = SEQ.incrementAndGet();
        long clientA = newRefund("client", receiptA, null, "已核实", LocalDate.of(2026, 11, 4), "TX-R-A", "客户退款甲");
        long clientB = newRefund("client", receiptB, null, "已核实", LocalDate.of(2026, 11, 9), "TX-R-B", "客户退款乙");
        long expenseA = newRefund("expense", null, paymentA, "已核实", LocalDate.of(2026, 11, 5), "TX-R-C", "费用回流甲");
        newRefund("client", receiptA, null, "已作废", LocalDate.of(2026, 11, 4), "TX-R-D", "作废退款");

        // 组合：client + 期间 + receiptA + 已核实
        PageResult<FinRefundDO> combined = refundMapper.selectPage(page(1, 10), orgId, "已核实",
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), receiptA, "client", null);
        assertThat(combined.getTotal()).isEqualTo(1);
        assertThat(combined.getList()).extracting(FinRefundDO::getId).containsExactly(clientA);

        // 方向筛选：expense 只出费用回流
        PageResult<FinRefundDO> expenseOnly = refundMapper.selectPage(page(1, 10), orgId, null,
                null, null, null, "expense", null);
        assertThat(expenseOnly.getTotal()).isEqualTo(1);
        assertThat(expenseOnly.getList().get(0).getId()).isEqualTo(expenseA);

        // 不加筛选：4 行全出；receiptId 语义保留（按原收款单过滤）
        PageResult<FinRefundDO> all = refundMapper.selectPage(page(1, 10), orgId, null, null, null, null, null, null);
        assertThat(all.getTotal()).isEqualTo(4);
        assertThat(all.getList()).extracting(FinRefundDO::getId)
                .containsExactlyInAnyOrder(clientA, clientB, expenseA, refundIdOf("TX-R-D"));

        PageResult<FinRefundDO> byReceipt = refundMapper.selectPage(page(1, 10), orgId, null,
                null, null, receiptB, null, null);
        assertThat(byReceipt.getTotal()).isEqualTo(1);
        assertThat(byReceipt.getList().get(0).getId()).isEqualTo(clientB);
    }

    // ===== 应付：status + projectId + keyword；DO 无到期日字段，签名不含任何日期参数 =====

    @Test
    void payablesPageFiltersByStatusProjectAndKeyword() {
        long inA = newPayable("进行中", 301L, "材料供应商甲", "AG-A");
        newPayable("已结清", 301L, "材料供应商乙", "AG-B");
        newPayable("进行中", 999L, "材料供应商丙", "AG-C");

        PageResult<FinPayableDO> result = payableMapper.selectPage(page(1, 10), orgId, "进行中", 301L, null);
        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getList()).extracting(FinPayableDO::getId).containsExactly(inA);

        PageResult<FinPayableDO> byKeyword = payableMapper.selectPage(page(1, 10), orgId, null, null, "AG-B");
        assertThat(byKeyword.getTotal()).isEqualTo(1);
        assertThat(byKeyword.getList().get(0).getStatus()).isEqualTo("已结清");
    }

    // ===== 异常资金：status + datePendingOnly + businessDate 区间 + keyword =====

    @Test
    void abnormalFundsPageFiltersByStatusDatePendingAndBusinessDate() {
        long pendingNullDate = newAbnormalFund("已核待关联", null, "王某", "期次一", "TX-AB-A", "原单缺失");
        long pendingWithDate = newAbnormalFund("已核待关联", LocalDate.of(2026, 11, 6), "李某", "期次二", "TX-AB-B", "超上限");
        newAbnormalFund("已关联", LocalDate.of(2026, 11, 7), "赵某", "期次三", "TX-AB-C", "已转正"); // 状态不符
        newAbnormalFund("已核待关联", LocalDate.of(2026, 10, 15), "钱某", "期次四", "TX-AB-D", "期间外");

        // datePendingOnly=true → 仅 businessDate IS NULL（沿用旧端点待归月语义）
        PageResult<FinAbnormalFundDO> datePending = abnormalFundMapper.selectPage(page(1, 10), orgId,
                "已核待关联", true, null, null, null);
        assertThat(datePending.getTotal()).isEqualTo(1);
        assertThat(datePending.getList()).extracting(FinAbnormalFundDO::getId).containsExactly(pendingNullDate);

        // 业务日期区间 + 状态
        PageResult<FinAbnormalFundDO> inRange = abnormalFundMapper.selectPage(page(1, 10), orgId,
                "已核待关联", false, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), null);
        assertThat(inRange.getTotal()).isEqualTo(1);
        assertThat(inRange.getList()).extracting(FinAbnormalFundDO::getId).containsExactly(pendingWithDate);

        // keyword（counterparty/stageOrCategory/transactionNo/reason 任一命中）
        PageResult<FinAbnormalFundDO> byKeyword = abnormalFundMapper.selectPage(page(1, 10), orgId,
                null, false, null, null, "TX-AB-C");
        assertThat(byKeyword.getTotal()).isEqualTo(1);
    }

    // ===== 旧端点兼容：selectByCompany 未分页 List 语义不变 =====

    /**
     * L3 组合反例锁定（批4 硬性交付，来自批2 窄审）：datePendingOnly=true 与 businessDate 区间同传时，
     * SQL 同时要求 business_date IS NULL 且 business_date 落在区间内 —— 恒为空集。
     * 前端 API 封装层已做防御性互斥（待归月模式剔除区间），本测试锁定后端语义防止未来漂移：
     * 组合态必须为空集，提醒调用方不要同时传。
     */
    @Test
    void abnormalFundsDatePendingOnlyCombinedWithBusinessDateRangeYieldsEmptySet() {
        newAbnormalFund("已核待关联", null, "孙某", "期次五", "TX-AB-E", "待归月样本");
        newAbnormalFund("已核待关联", LocalDate.of(2026, 11, 8), "周某", "期次六", "TX-AB-F", "期内样本");

        PageResult<FinAbnormalFundDO> combined = abnormalFundMapper.selectPage(page(1, 10), orgId,
                "已核待关联", true, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), null);
        assertThat(combined.getTotal()).isZero();
        assertThat(combined.getList()).isEmpty();
    }

    @Test
    void legacySelectByCompanyStillReturnsUnpagedList() {
        long inA = newReceipt("已登记", LocalDate.of(2026, 11, 5), 101L, "bank", "manual", "兼容甲", "TX-L-A");
        newReceipt("待登记", LocalDate.of(2026, 11, 6), 101L, "bank", "manual", "兼容乙", "TX-L-B");
        List<FinReceiptDO> all = receiptMapper.selectByCompany(orgId, null);
        assertThat(all).extracting(FinReceiptDO::getId).contains(inA);
        List<FinReceiptDO> registered = receiptMapper.selectByCompany(orgId, "已登记");
        assertThat(registered).extracting(FinReceiptDO::getId).containsExactly(inA);

        long refundA = newRefund("client", SEQ.incrementAndGet(), null, "已核实", LocalDate.of(2026, 11, 5), "TX-L-C", "退款甲");
        assertThat(refundMapper.selectByCompany(orgId)).extracting(FinRefundDO::getId).contains(refundA);
    }

    // ===== 数据构造 =====

    private long newReceipt(String status, LocalDate receivedDate, Long projectId, String method,
                            String sourceType, String name, String txnNo) {
        return newReceiptFor(orgId, status, receivedDate, projectId, method, sourceType, name, txnNo);
    }

    private long newReceiptFor(long companyId, String status, LocalDate receivedDate, Long projectId, String method,
                               String sourceType, String name, String txnNo) {
        long id = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO fr_fin_receipt (id, company_id, source_type, name, amount_cents, status, "
                        + "received_date, registrar_user_id, method, transaction_no, project_id, source_ref) "
                        + "VALUES (?, ?, ?, ?, 10000, ?, ?, ?, ?, ?, ?, ?)",
                id, companyId, sourceType, name, status, receivedDate, userId, method, txnNo, projectId, "REF-" + txnNo);
        return id;
    }

    private long newPayment(String status, LocalDate payDate, Long projectId, String txnNo) {
        long id = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO fr_fin_payment (id, payable_id, project_id, company_id, amount_cents, status, "
                        + "pay_date, operator_user_id, transaction_no, voided_at) "
                        + "VALUES (?, ?, ?, ?, 10000, ?, ?, ?, ?, ?)",
                id, SEQ.incrementAndGet(), projectId, orgId, status, payDate, userId, txnNo,
                "已作废".equals(status) ? java.time.OffsetDateTime.now() : null);
        return id;
    }

    private long newRefund(String direction, Long receiptId, Long paymentId, String status, LocalDate date,
                           String txnNo, String reason) {
        long id = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO fr_fin_refund (id, receipt_id, payment_id, company_id, amount_cents, status, reason, "
                        + "operator_user_id, refund_direction, date, transaction_no, voided_at) "
                        + "VALUES (?, ?, ?, ?, 10000, ?, ?, ?, ?, ?, ?, ?)",
                id, receiptId, paymentId, orgId, status, reason, userId, direction, date, txnNo,
                "已作废".equals(status) ? java.time.OffsetDateTime.now() : null);
        return id;
    }

    @Test @org.springframework.transaction.annotation.Transactional
    void visibleEngineeringNamesAreSearchedBeforeCountAndPage() {
        jdbc.execute("CREATE TEMP TABLE fr_eng_expense(company_id bigint,payable_id bigint,name text)");
        jdbc.execute("CREATE TEMP TABLE fr_eng_supply(company_id bigint,payable_id bigint,material_id bigint)");
        jdbc.execute("CREATE TEMP TABLE fr_eng_material(id bigint,company_id bigint,name text)");
        long a=newPayable("进行中",301L,"供应商","A"), b=newPayable("进行中",301L,"供应商","B");
        jdbc.update("UPDATE fr_fin_payable SET stage='engineering' WHERE id IN (?,?)",a,b);
        jdbc.update("INSERT INTO fr_eng_expense VALUES(?,?,'工地吊装租赁')",orgId,a);
        jdbc.update("INSERT INTO fr_eng_material VALUES(99,?,'工地吊装机械')",orgId);
        jdbc.update("INSERT INTO fr_eng_supply VALUES(?,?,99)",orgId,b);
        var first=payableMapper.selectPage(page(1,1),orgId,null,null,"工地吊装");
        var second=payableMapper.selectPage(page(2,1),orgId,null,null,"工地吊装");
        assertThat(first.getTotal()).isEqualTo(2);
        assertThat(List.of(first.getList().get(0).getName(),second.getList().get(0).getName())).containsExactlyInAnyOrder("工地吊装租赁","工地吊装机械");
    }

    private long newPayable(String status, Long projectId, String recipient, String agreementRef) {
        long id = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO fr_fin_payable (id, company_id, project_id, stage, total_cents, recipient, status, agreement_ref) "
                        + "VALUES (?, ?, ?, '一期', 10000, ?, ?, ?)",
                id, orgId, projectId, recipient, status, agreementRef);
        return id;
    }

    private long newAbnormalFund(String status, LocalDate businessDate, String counterparty,
                                 String stageOrCategory, String txnNo, String reason) {
        long id = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO fr_fin_abnormal_fund (id, company_id, fund_direction, amount_cents, counterparty, "
                        + "stage_or_category, business_date, transaction_no, reason, status, operator_user_id) "
                        + "VALUES (?, ?, '客户收款', 10000, ?, ?, ?, ?, ?, ?, ?)",
                id, orgId, counterparty, stageOrCategory, businessDate, txnNo, reason, status, userId);
        return id;
    }

    private long refundIdOf(String txnNo) {
        return jdbc.queryForObject("SELECT id FROM fr_fin_refund WHERE transaction_no = ?", Long.class, txnNo);
    }
}
