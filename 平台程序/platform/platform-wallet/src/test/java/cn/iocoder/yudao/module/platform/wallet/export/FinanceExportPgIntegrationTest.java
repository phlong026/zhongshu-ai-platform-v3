package cn.iocoder.yudao.module.platform.wallet.export;

import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 批3：导出端真实 PG17 回归（Testcontainers + PlatformPgIntegrationTest）。
 *
 * <p>测试库用最小 DDL 夹具建 fr_fin_* 四表 + fr_fin_abnormal_fund（仅测试夹具，
 * 非生产迁移/种子/DDL 变更），列名逐字取自本域迁移 V20261101.804 / V20261104.820 /
 * V20261104.827 / V20261104.829。验证：组织隔离、逐表组合筛选等价批2 Mapper、
 * 5 万上限、更正链 R4 与统计口径、月度对账汇总口径对齐 FinReconciliationService。
 *
 * <p>Codex P1②/P2② 补测：负净实收数值单元格、payments/payables 真实导出、
 * 月度汇总 8 分量逐列核对、异常资金夹具非空。
 */
class FinanceExportPgIntegrationTest extends PlatformPgIntegrationTest {

    private static final String SERVICE = "cn.iocoder.yudao.module.platform.wallet.export.FinanceExportService";
    private static final AtomicLong SEQ = new AtomicLong(9_600_000_000L + System.nanoTime() % 300_000_000L);

    @Autowired DataSource dataSource;
    private JdbcTemplate jdbc;
    private long orgId;
    private long otherOrg;
    private long userId = 77L;

    private static Class<?> serviceClass() {
        try {
            return Class.forName(SERVICE);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    @BeforeEach
    void schema() {
        assertThat(serviceClass()).isNotNull();
        jdbc = new JdbcTemplate(dataSource);
        orgId = SEQ.incrementAndGet();
        otherOrg = SEQ.incrementAndGet();
        // 最小 DDL 夹具（测试库专用；列名与生产迁移逐字一致，不含约束/索引，避免依赖其它表）
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_fin_receipt (id BIGINT, company_id BIGINT, project_id BIGINT, "
                + "customer_id BIGINT, source_type VARCHAR(16), source_ref VARCHAR(128), name VARCHAR(128), "
                + "amount_cents BIGINT, status VARCHAR(16), received_date DATE, registered_at TIMESTAMPTZ, "
                + "registrar_user_id BIGINT, method VARCHAR(16), transaction_no VARCHAR(64), voucher_file_id BIGINT, "
                + "revision_version INT, void_reason VARCHAR(255), void_by_user_id BIGINT, create_time TIMESTAMPTZ)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_fin_payable (id BIGINT, company_id BIGINT, project_id BIGINT, "
                + "stage VARCHAR(64), total_cents BIGINT, recipient VARCHAR(128), status VARCHAR(16), "
                + "agreement_ref VARCHAR(128), create_time TIMESTAMPTZ)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_fin_payment (id BIGINT, payable_id BIGINT, project_id BIGINT, "
                + "company_id BIGINT, amount_cents BIGINT, pending_link_cents BIGINT, link_status VARCHAR(16), "
                + "status VARCHAR(16), pay_date DATE, operator_user_id BIGINT, transaction_no VARCHAR(64), "
                + "voucher_file_id BIGINT, void_reason VARCHAR(255), voided_at TIMESTAMPTZ, create_time TIMESTAMPTZ)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_fin_refund (id BIGINT, receipt_id BIGINT, company_id BIGINT, "
                + "refund_direction VARCHAR(16), payment_id BIGINT, amount_cents BIGINT, status VARCHAR(16), "
                + "reason VARCHAR(255), operator_user_id BIGINT, replaced_by_id BIGINT, replaces_id BIGINT, "
                + "date DATE, transaction_no VARCHAR(64), voucher_file_id BIGINT, void_reason VARCHAR(255), "
                + "voided_at TIMESTAMPTZ, create_time TIMESTAMPTZ)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_fin_abnormal_fund (id BIGINT, company_id BIGINT, "
                + "fund_direction VARCHAR(32), amount_cents BIGINT, business_date DATE, status VARCHAR(16), "
                + "create_time TIMESTAMPTZ)");
        for (String table : List.of("fr_fin_receipt", "fr_fin_payable", "fr_fin_payment", "fr_fin_refund",
                "fr_fin_abnormal_fund")) {
            jdbc.update("DELETE FROM " + table + " WHERE company_id IN (?,?)", orgId, otherOrg);
        }
    }

    /** 通过反射调用 Service 的静态纯查询入口（真实 PG）：返回 CSV 字节。 */
    private byte[] export(String table, Map<String, String> filters, long org, long actor) throws Exception {
        Class<?> c = serviceClass();
        assertThat(c).isNotNull();
        java.lang.reflect.Method m = c.getDeclaredMethod("exportForTest", JdbcTemplate.class, String.class,
                Map.class, long.class, long.class);
        m.setAccessible(true);
        return (byte[]) m.invoke(null, jdbc, table, filters, org, actor);
    }

    private static String csv(byte[] body) {
        return new String(body, 3, body.length - 3, StandardCharsets.UTF_8);
    }

    /** 按 CSV 行（含引号字段）切出第 N 行的单元格；用于逐列核对 8 分量。 */
    private static List<String> dataCells(byte[] body) {
        String text = csv(body);
        String[] lines = text.split("\r\n");
        // lines[0]=表头，lines[1]=数据行
        String row = lines[1];
        List<String> cells = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < row.length(); i++) {
            char ch = row.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < row.length() && row.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                cells.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        cells.add(cur.toString());
        return cells;
    }

    private long receipt(String status, LocalDate date, long project, String method, String source, String name,
                         String txn, long company, String voidReason, long amountCents) {
        long id = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO fr_fin_receipt (id,company_id,project_id,source_type,source_ref,name,amount_cents,status,"
                        + "received_date,registered_at,registrar_user_id,method,transaction_no,revision_version,void_reason,create_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,now(),?,?,?,?,?,now())",
                id, company, project, source, "REF-" + txn, name, amountCents, status, date, userId, method, txn, 1, voidReason);
        return id;
    }

    private long receipt(String status, LocalDate date, long project, String method, String source, String name,
                         String txn, long company, String voidReason) {
        return receipt(status, date, project, method, source, name, txn, company, voidReason, 10000);
    }

    private long refund(String direction, String status, LocalDate date, Long replacedBy, Long replaces,
                        String voidReason, long company, long amountCents) {
        long id = SEQ.incrementAndGet();
        long receiptId = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO fr_fin_refund (id,receipt_id,company_id,refund_direction,amount_cents,status,reason,"
                        + "operator_user_id,replaced_by_id,replaces_id,date,transaction_no,void_reason,voided_at,create_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())",
                id, receiptId, company, direction, amountCents, status, "退款", userId, replacedBy, replaces, date,
                "TX-" + id, voidReason, "已作废".equals(status) ? java.time.OffsetDateTime.now() : null);
        return id;
    }

    private long refund(String direction, String status, LocalDate date, Long replacedBy, Long replaces,
                        String voidReason, long company) {
        return refund(direction, status, date, replacedBy, replaces, voidReason, company, 10000);
    }

    private long payment(String status, LocalDate payDate, long project, String txn, long company) {
        long id = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO fr_fin_payment (id,payable_id,project_id,company_id,amount_cents,pending_link_cents,"
                        + "link_status,status,pay_date,operator_user_id,transaction_no,void_reason,voided_at,create_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,now())",
                id, SEQ.incrementAndGet(), project, company, 25000, 5000, "LINKED", status, payDate, userId, txn,
                "已作废".equals(status) ? "撤销" : null,
                "已作废".equals(status) ? java.time.OffsetDateTime.now() : null);
        return id;
    }

    private long payable(String status, long project, String recipient, String agreement, long company) {
        long id = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO fr_fin_payable (id,company_id,project_id,stage,total_cents,recipient,status,agreement_ref,create_time) "
                        + "VALUES (?,?,?,'一期',30000,?,?,?,now())",
                id, company, project, recipient, status, agreement);
        return id;
    }

    private long abnormal(String status, LocalDate businessDate, long amountCents, long company) {
        long id = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO fr_fin_abnormal_fund (id,company_id,fund_direction,amount_cents,business_date,status,create_time) "
                        + "VALUES (?,?,'客户收款',?,?,?,now())",
                id, company, amountCents, businessDate, status);
        return id;
    }

    @Test
    void organizationIsolationAndReceiptsFiltersMatchPageContract() throws Exception {
        long mine = receipt("已登记", LocalDate.of(2026, 11, 5), 101L, "bank", "manual", "本公司收款", "TX-A", orgId, null);
        receipt("已登记", LocalDate.of(2026, 11, 5), 101L, "bank", "manual", "他公司收款", "TX-B", otherOrg, null);
        receipt("待登记", LocalDate.of(2026, 11, 6), 101L, "bank", "manual", "未登记", "TX-C", orgId, null);
        receipt("已登记", LocalDate.of(2026, 10, 30), 101L, "bank", "manual", "期间外", "TX-D", orgId, null);

        byte[] body = export("receipts", Map.of("receivedDateFrom", "2026-11-01", "receivedDateTo", "2026-11-30",
                "status", "已登记", "projectId", "101", "method", "bank", "sourceType", "manual"), orgId, userId);
        String text = csv(body);
        assertThat(text).contains("本公司收款", "金额（元）");
        assertThat(text).doesNotContain("他公司收款", "未登记", "期间外");
        assertThat(text).contains("100.00");
        assertThat(text).contains(String.valueOf(mine));
    }

    @Test
    void voidedRowsAppearInDetailButNotInSummary() throws Exception {
        receipt("已登记", LocalDate.of(2026, 11, 5), 101L, "bank", "manual", "有效收款", "TX-V1", orgId, null);
        long voided = receipt("已登记", LocalDate.of(2026, 11, 5), 101L, "bank", "manual", "作废收款", "TX-V2", orgId, "误录撤销");
        byte[] body = export("receipts", Map.of(), orgId, userId);
        String text = csv(body);
        assertThat(text).contains("有效收款", "作废收款", "误录撤销", String.valueOf(voided));
        assertThat(text).contains("\"明细行数\",\"2\"", "\"有效汇总金额（元）\",\"100.00\"");
        assertThat(text).doesNotContain("\"有效汇总金额（元）\",\"200.00\"");
    }

    @Test
    void refundCorrectionChainFollowsR4() throws Exception {
        long oldReplaced = refund("client", "已核实", LocalDate.of(2026, 11, 5), 999L, null, null, orgId); // 被替代旧单
        long newCorrected = refund("client", "已核实", LocalDate.of(2026, 11, 6), null, oldReplaced, null, orgId); // 有效更正新单
        long voided = refund("client", "已作废", LocalDate.of(2026, 11, 7), null, null, "撤销", orgId); // 作废单
        byte[] body = export("refunds", Map.of(), orgId, userId);
        String text = csv(body);
        assertThat(text).contains("更正链（原单）", "更正链（被更正标记）", "更正");
        assertThat(text).contains(String.valueOf(oldReplaced), String.valueOf(newCorrected), String.valueOf(voided));
        assertThat(text).contains("已由 #999 替代");
        assertThat(text).contains("\"明细行数\",\"3\"", "\"有效汇总金额（元）\",\"100.00\"");
    }

    @Test
    void fiftyThousandRowLimitUsesDedicatedGuard() {
        jdbc.update("INSERT INTO fr_fin_receipt (id,company_id,project_id,source_type,source_ref,name,amount_cents,"
                        + "status,received_date,registered_at,registrar_user_id,method,transaction_no,revision_version,"
                        + "void_reason,create_time) "
                        + "SELECT 9700000000 + g, ?, 101, 'manual', 'REF-LIMIT', '上限样本', 10000, '已登记', "
                        + "DATE '2026-11-05', now(), ?, 'bank', 'TX-LIMIT-' || g, 1, NULL, now() "
                        + "FROM generate_series(1, 50001) g",
                orgId, userId);
        Throwable thrown = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> export("receipts", Map.of(), orgId, userId));
        assertThat(thrown.getCause()).isInstanceOf(
                cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(((cn.iocoder.yudao.framework.common.exception.ServiceException) thrown.getCause()).getCode())
                .isEqualTo(400);
        assertThat(thrown.getCause().getMessage()).contains("收窄筛选");
        jdbc.update("DELETE FROM fr_fin_receipt WHERE company_id = ? AND transaction_no LIKE 'TX-LIMIT-%'", orgId);
    }

    // ===== Codex P2②：payments / payables 真实导出 =====

    @Test
    void paymentsExportUsesRealSqlAndMoneyColumns() throws Exception {
        long valid = payment("已核实", LocalDate.of(2026, 11, 3), 201L, "TX-PAY-1", orgId);
        payment("已作废", LocalDate.of(2026, 11, 4), 201L, "TX-PAY-2", orgId); // 作废单明细照出
        payment("已核实", LocalDate.of(2026, 11, 3), 999L, "TX-PAY-3", orgId); // 项目不符
        byte[] body = export("payments", Map.of("status", "已核实", "payDateFrom", "2026-11-01",
                "payDateTo", "2026-11-30", "projectId", "201"), orgId, userId);
        String text = csv(body);
        assertThat(text).contains("付款单号", "待关联金额（元）", "关联状态", "作废原因", "作废时间");
        assertThat(text).contains(String.valueOf(valid), "250.00", "50.00"); // 金额分→元
        assertThat(text).doesNotContain("TX-PAY-2", "TX-PAY-3", "'-"); // 无负号文本化
    }

    @Test
    void payablesExportUsesRealSqlAndDoesNotPromiseDueDate() throws Exception {
        long valid = payable("进行中", 301L, "材料供应商甲", "AG-PAYABLE-1", orgId);
        payable("已结清", 301L, "材料供应商乙", "AG-PAYABLE-2", orgId);
        byte[] body = export("payables", Map.of("status", "进行中", "projectId", "301"), orgId, userId);
        String text = csv(body);
        assertThat(text).contains("应付单号", "阶段", "金额（元）", "收款方", "协议依据", "创建时间");
        assertThat(text).doesNotContain("到期日", "业务日期");
        assertThat(text).contains(String.valueOf(valid), "材料供应商甲", "300.00");
        assertThat(text).doesNotContain("材料供应商乙");
    }

    // ===== Codex P2②：月度汇总 8 分量逐列 + 异常资金夹具非空 =====

    @Test
    void reconciliationSummaryChecksAllEightComponentsColumnByColumn() throws Exception {
        // 毛实收：2×100.00 = 200.00（已登记，月内）
        receipt("已登记", LocalDate.of(2026, 11, 5), 101L, "bank", "manual", "收款甲", "TX-R1", orgId, null);
        receipt("已登记", LocalDate.of(2026, 11, 8), 101L, "cash", "manual", "收款乙", "TX-R2", orgId, null);
        receipt("待登记", LocalDate.of(2026, 11, 9), 101L, "bank", "manual", "未登记", "TX-R3", orgId, null);
        // 客户退款：1×100.00 = 100.00（client + 已核实 + 未被替代 + 月内）
        refund("client", "已核实", LocalDate.of(2026, 11, 6), null, null, null, orgId);
        refund("client", "已核实", LocalDate.of(2026, 11, 7), 888L, null, null, orgId); // 被替代旧单，不计
        refund("expense", "已核实", LocalDate.of(2026, 11, 7), null, null, null, orgId); // 费用回流，不进 client
        refund("client", "已作废", LocalDate.of(2026, 11, 7), null, null, "撤销", orgId); // 作废，不计
        // 异常资金夹具非空：2 笔已核待关联，其中 1 笔 business_date IS NULL（待归月）
        abnormal("已核待关联", null, 7000, orgId);
        abnormal("已核待关联", LocalDate.of(2026, 11, 6), 3000, orgId);
        abnormal("已关联", LocalDate.of(2026, 11, 7), 9999, orgId); // 状态不符，不计

        byte[] body = export("reconciliation", Map.of("month", "2026-11"), orgId, userId);
        List<String> cells = dataCells(body);
        assertThat(cells).containsExactly(
                "2026-11",       // 月份
                "200.00",        // 毛实收（元）
                "100.00",        // 客户退款（元）
                "100.00",        // 净实收（元）= 200 - 100
                "100.00",        // 异常待关联金额（元）= 7000+3000=10000 分
                "2",             // 异常待关联笔数
                "70.00",         // 待归月金额（元）= 7000 分（business_date IS NULL 子集）
                "1");            // 待归月笔数
    }

    // ===== Codex P1②：退款 > 毛实收时负净实收必须是受控数值单元格，不得被文本化 =====

    @Test
    void negativeNetReceiptIsNumericCellNotTextPrefixed() throws Exception {
        receipt("已登记", LocalDate.of(2026, 11, 5), 101L, "bank", "manual", "小额收款", "TX-NEG-1", orgId, null, 10000);
        refund("client", "已核实", LocalDate.of(2026, 11, 6), null, null, null, orgId, 20000); // 退款 200.00 > 毛实收 100.00
        byte[] body = export("reconciliation", Map.of("month", "2026-11"), orgId, userId);
        List<String> cells = dataCells(body);
        assertThat(cells.get(0)).isEqualTo("2026-11");
        assertThat(cells.get(1)).isEqualTo("100.00");   // 毛实收
        assertThat(cells.get(2)).isEqualTo("200.00");   // 客户退款
        assertThat(cells.get(3)).isEqualTo("-100.00");  // 净实收（负）：必须是不带 ' 前缀的数值
        String text = csv(body);
        assertThat(text).doesNotContain("'-100.00", "\"'-100.00\"", "'-"); // 不得文本化
        // 明细各金额列同样做数值单元格（收款 100.00 / 退款 200.00 / 待关联 0.00）
        String refunds = csv(export("refunds", Map.of(), orgId, userId));
        assertThat(refunds).contains("200.00");
        assertThat(refunds).doesNotContain("'-");
    }
}
