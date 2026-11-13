package cn.iocoder.yudao.module.platform.wallet.export;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 批3：CSV 逐表列清单、金额分→元、更正链 R4、作废单明细行、月度汇总独立结构的纯逻辑回归。
 *
 * <p>经反射调用 {@link FinanceExportService} 的两个静态纯函数（{@code headerFor(table)}
 * / {@code moneyYuan(long)} / {@code csvRow(List)}），红期生产类不存在时
 * {@code assertThat(method).isNotNull()} 干净断言红，绿期逐项转绿。
 * 真实 SQL 筛选/统计口径由 FinanceExportPgIntegrationTest（真实 PG17）覆盖。
 */
class FinanceExportCsvTest {

    private static final String SERVICE = "cn.iocoder.yudao.module.platform.wallet.export.FinanceExportService";

    private static Class<?> service() {
        try {
            return Class.forName(SERVICE);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private static Method method(String name, Class<?>... params) {
        Class<?> c = service();
        if (c == null) {
            return null;
        }
        try {
            Method m = c.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static List<String> header(String table) throws Exception {
        Method m = method("headerFor", String.class);
        assertThat(m).isNotNull();
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) m.invoke(null, table);
        return result;
    }

    private static String money(long cents) throws Exception {
        Method m = method("moneyYuan", long.class);
        assertThat(m).isNotNull();
        return (String) m.invoke(null, cents);
    }

    // ===== §8.2 逐表列清单（严格按 R5 表格逐字，非 DO 全字段） =====

    @Test
    void receiptsHeaderIsSeventeenColumnsFromSpec() throws Exception {
        assertThat(header("receipts")).containsExactly("收款单号", "项目", "客户", "来源类型", "来源单据", "名目",
                "金额（元）", "状态", "业务日期", "登记时间", "登记人", "收款方式", "交易号", "凭证",
                "修订版本", "作废原因", "作废人");
    }

    @Test
    void payablesHeaderIsEightColumnsAndPromisesNoDueDate() throws Exception {
        assertThat(header("payables")).containsExactly("应付单号", "项目", "阶段", "金额（元）", "收款方",
                "状态", "协议依据", "创建时间");
        assertThat(header("payables")).doesNotContain("到期日", "业务日期");
    }

    @Test
    void paymentsHeaderIsThirteenColumnsFromSpec() throws Exception {
        assertThat(header("payments")).containsExactly("付款单号", "关联应付单", "项目", "金额（元）",
                "待关联金额（元）", "关联状态", "状态", "付款日期", "经办人", "交易号", "凭证",
                "作废原因", "作废时间");
        // R4：收款/付款/应付三表不含更正链列
        assertThat(header("payments")).doesNotContain("更正链", "原单", "被更正标记");
    }

    @Test
    void refundsHeaderIncludesCorrectionChainAndMarkColumn() throws Exception {
        assertThat(header("refunds")).containsExactly("退款单号", "关联收款单", "方向", "费用回流关联付款单",
                "金额（元）", "状态", "原因", "日期", "交易号", "凭证", "更正链（原单）",
                "更正链（被更正标记）", "更正", "作废原因", "作废时间");
    }

    @Test
    void reconciliationHeaderIsIndependentEightComponentStructure() throws Exception {
        assertThat(header("reconciliation")).containsExactly("月份", "毛实收（元）", "客户退款（元）",
                "净实收（元）", "异常待关联金额（元）", "异常待关联笔数", "待归月金额（元）", "待归月笔数");
    }

    @Test
    void otherThreeTablesHaveNoCorrectionChainColumns() throws Exception {
        for (String table : List.of("receipts", "payments", "payables")) {
            assertThat(header(table)).doesNotContain("更正链（原单）", "更正链（被更正标记）", "更正");
        }
    }

    // ===== 金额分→元，两位小数 =====

    @Test
    void moneyConvertsCentsToYuanWithTwoDecimals() throws Exception {
        assertThat(money(10000)).isEqualTo("100.00");
        assertThat(money(1)).isEqualTo("0.01");
        assertThat(money(123456789)).isEqualTo("1234567.89");
        assertThat(money(0)).isEqualTo("0.00");
        assertThat(money(-10000)).isEqualTo("-100.00"); // 负净实收（退款>毛实收）
    }

    /** Codex P1②：金额走受控数值类型（BigDecimal）→ CSV 数值单元格，不得被公式注入前缀文本化。 */
    @Test
    void numericMoneyCellKeepsNegativeValueAsNumber() throws Exception {
        Method m = method("csvRow", List.class);
        assertThat(m).isNotNull();
        String row = (String) m.invoke(null, java.util.Arrays.asList(
                new java.math.BigDecimal("-100.00"), new java.math.BigDecimal("200.00"),
                java.math.BigDecimal.ZERO, "=SUM(A1)", null));
        assertThat(row).startsWith("\"-100.00\",\"200.00\",\"0\",\"'=SUM(A1)\"");
        assertThat(row).doesNotContain("'-100.00", "\"'-", "'200.00");
        assertThat(row).endsWith("\r\n");
    }

    // ===== CSV 转义与 UTF-8 BOM（沿用 ZhongshuExportWorker.appendRow 形态） =====

    @Test
    void csvRowQuotesAndEscapesFormulaInjection() throws Exception {
        Method m = method("csvRow", List.class);
        assertThat(m).isNotNull();
        String row = (String) m.invoke(null, java.util.Arrays.asList("=SUM(A1)", "含,逗号", "含\"引号", null, 100));
        assertThat(row).startsWith("\"'=SUM(A1)\"");
        assertThat(row).contains("\"含,逗号\"", "\"含\"\"引号\"", "\"\"");
        assertThat(row).endsWith("\r\n");
    }

    // ===== 更正链 R4 与统计口径（纯函数，绿期实现） =====

    @Test
    void correctionMarkUsesReplacedById() throws Exception {
        Method m = method("correctionMark", Object.class);
        assertThat(m).isNotNull();
        assertThat((String) m.invoke(null, 88L)).contains("已由 #88 替代");
        assertThat((String) m.invoke(null, new Object[]{null})).isEmpty();
        assertThat((String) m.invoke(null, "")).isEmpty();
    }

    @Test
    void summaryEligibilityFollowsPerTableRules() throws Exception {
        Method m = method("summaryEligible", String.class, Map.class);
        assertThat(m).isNotNull();
        // 实收只计已登记；作废单（voidReason 非空）不计汇总
        assertThat((boolean) m.invoke(null, "receipts", Map.of("status", "已登记", "void_reason", ""))).isTrue();
        assertThat((boolean) m.invoke(null, "receipts", Map.of("status", "待登记", "void_reason", ""))).isFalse();
        assertThat((boolean) m.invoke(null, "receipts", Map.of("status", "已登记", "void_reason", "误录"))).isFalse();
        // 付款只计已核实；作废不计
        assertThat((boolean) m.invoke(null, "payments", Map.of("status", "已核实", "void_reason", ""))).isTrue();
        assertThat((boolean) m.invoke(null, "payments", Map.of("status", "已作废", "void_reason", "撤销"))).isFalse();
        // 退款只计已核实 + 排除被替代旧单（replaced_by_id 非空）；作废不计
        assertThat((boolean) m.invoke(null, "refunds", Map.of("status", "已核实", "void_reason", "", "replaced_by_id", ""))).isTrue();
        assertThat((boolean) m.invoke(null, "refunds", Map.of("status", "已核实", "void_reason", "", "replaced_by_id", "77"))).isFalse();
        assertThat((boolean) m.invoke(null, "refunds", Map.of("status", "已作废", "void_reason", "撤销", "replaced_by_id", ""))).isFalse();
        // 保留有效更正新单（replaces_id 非空但 replaced_by_id 空）
        assertThat((boolean) m.invoke(null, "refunds", Map.of("status", "已核实", "void_reason", "", "replaced_by_id", "", "replaces_id", "66"))).isTrue();
        // 应付：本批导出明细行照出，但不参与现金口径汇总（§8.1 逐表口径仅实收/付款/退款）
        assertThat((boolean) m.invoke(null, "payables", Map.of("status", "进行中", "void_reason", ""))).isFalse();
    }
}
