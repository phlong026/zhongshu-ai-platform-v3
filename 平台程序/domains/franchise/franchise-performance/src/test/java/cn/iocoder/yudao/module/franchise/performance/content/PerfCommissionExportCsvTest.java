package cn.iocoder.yudao.module.franchise.performance.content;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfCommissionPolicyDO;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfCommissionReleaseDO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * P1 尾批③：绩效报表导出 CSV 形态纯函数（不触库）。
 *
 * <p>覆盖 §8.1 通用导出决策：UTF-8 BOM、中文表头（金额列注明「（元）」）、分→元两位小数、
 * 文本列公式注入防护（= + - @ 前置单引号）、金额列不做前缀、空值/引号/CRLF 转义。
 */
class PerfCommissionExportCsvTest {

    @Test
    void headerIsTenChineseColumnsWithYuanSuffix() {
        assertThat(PerfCommissionExportService.header()).containsExactly(
                "员工ID", "员工姓名", "涉及项目数", "提成总额（元）", "已生成应付（元）",
                "已付（元）", "未付（元）", "已付清项目数", "待核对项目数", "未约定项目数");
    }

    @Test
    void monthAcceptsStrictYearMonthAndTrims() {
        assertThat(PerfCommissionExportService.validateMonth("2026-11")).isEqualTo("2026-11");
        assertThat(PerfCommissionExportService.validateMonth(" 2026-01 ")).isEqualTo("2026-01");
        assertThat(PerfCommissionExportService.validateMonth("2026-12")).isEqualTo("2026-12");
    }

    @Test
    void monthRejectsMalformedValuesWithBadRequest() {
        for (String month : Arrays.asList(null, "", "2026-1", "2026-13", "2026-00", "202611", "2026/11", "abc")) {
            ServiceException error = catchThrowableOfType(
                    () -> PerfCommissionExportService.validateMonth(month), ServiceException.class);
            assertThat(error).as(String.valueOf(month)).isNotNull();
            assertThat(error.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
            assertThat(error.getMessage()).isEqualTo("月份格式须为 YYYY-MM");
        }
    }

    @Test
    void stageAmountMappingIsSharedByReconcileAndExport() {
        FrPerfCommissionPolicyDO policy = new FrPerfCommissionPolicyDO()
                .setStartCents(10_000L).setBaseDoneCents(20_000L).setToppingCents(30_000L);
        assertThat(PerfCommissionService.stageAmountOf(policy,
                FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER)).isEqualTo(10_000L);
        assertThat(PerfCommissionService.stageAmountOf(policy,
                FrPerfCommissionReleaseDO.STAGE_START)).isEqualTo(10_000L);           // 存量映射
        assertThat(PerfCommissionService.stageAmountOf(policy,
                FrPerfCommissionReleaseDO.STAGE_BASE_DONE)).isEqualTo(20_000L);
        assertThat(PerfCommissionService.stageAmountOf(policy,
                FrPerfCommissionReleaseDO.STAGE_CAP_DONE)).isEqualTo(30_000L);
        assertThat(PerfCommissionService.stageAmountOf(policy,
                FrPerfCommissionReleaseDO.STAGE_TOPPING)).isEqualTo(30_000L);         // 存量映射
        // 与流式导出的 primitive 重载同源
        assertThat(PerfCommissionService.stageAmountOf(10_000L, 20_000L, 30_000L,
                FrPerfCommissionReleaseDO.STAGE_BASE_DONE)).isEqualTo(20_000L);
        assertThat(PerfCommissionService.stageAmountOf(null, null, null,
                FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER)).isZero();
        assertThat(PerfCommissionService.stageAmountOf((FrPerfCommissionPolicyDO) null,
                FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER)).isZero();
    }

    @Test
    void engineeringStageSignatureIgnoresOnlyCollectionOrder() {
        String canonical = "required:100|c:3:40:0|c:9:60:5";
        String legacyReverse = "required:100|c:9:60:5|c:3:40:0";

        assertThat(PerfCommissionService.sameStageSignature(canonical, legacyReverse)).isTrue();
        assertThat(PerfCommissionService.sameStageSignature(canonical,
                "required:101|c:9:60:5|c:3:40:0")).isFalse();
        assertThat(PerfCommissionService.sameStageSignature(canonical,
                "required:100|c:9:61:5|c:3:40:0")).isFalse();
        assertThat(PerfCommissionService.sameStageSignature(canonical,
                "required:100|c:9:60:6|c:3:40:0")).isFalse();
        assertThat(PerfCommissionService.sameStageSignature(canonical,
                "required:100|c:4:40:0|c:9:60:5")).isFalse();
        assertThat(PerfCommissionService.sameStageSignature(canonical,
                "required:100|c:9:60:5")).isFalse();
        assertThat(PerfCommissionService.sameStageSignature(canonical,
                "required:100|c:9:60:5|c:3:40:0|c:3:40:0")).isFalse();
    }

    @Test
    void engineeringStageSignatureRejectsNonCanonicalDecimals() {
        String canonical = "required:100|c:3:40:0|c:9:60:5";

        assertThat(PerfCommissionService.sameStageSignature(canonical,
                "required:+100|c:9:60:5|c:3:40:0")).isFalse();
        assertThat(PerfCommissionService.sameStageSignature(canonical,
                "required:100|c:09:060:5|c:03:040:0")).isFalse();
        assertThat(PerfCommissionService.sameStageSignature(canonical,
                "required:100|c:9:60:5|c:3:40:-0")).isFalse();
        assertThat(PerfCommissionService.sameStageSignature(canonical,
                "required:9223372036854775808|c:9:60:5|c:3:40:0")).isFalse();
        assertThat(PerfCommissionService.sameStageSignature(canonical,
                "required:-100|c:9:60:5|c:3:40:0")).isFalse();
        assertThat(PerfCommissionService.sameStageSignature(canonical,
                "required:100|c:0:40:0|c:9:60:5")).isFalse();
        assertThat(PerfCommissionService.sameStageSignature(canonical,
                "required:100|c:3:0:0|c:9:60:5")).isFalse();
        assertThat(PerfCommissionService.sameStageSignature(canonical,
                "required:100|c:3:40:-1|c:9:60:5")).isFalse();
    }

    @Test
    void unifiedAndUnknownStageSignaturesRemainLiteral() {
        String unified = "required:100|unified:9:SIGN_AFTER:100";
        assertThat(PerfCommissionService.sameStageSignature(unified, unified)).isTrue();
        assertThat(PerfCommissionService.sameStageSignature(unified,
                "required:100|unified:9:SIGN_AFTER:101")).isFalse();
        assertThat(PerfCommissionService.sameStageSignature("legacy:a|legacy:b",
                "legacy:b|legacy:a")).isFalse();
        assertThat(PerfCommissionService.sameStageSignature(
                "required:100|c:broken", "required:100|c:broken")).isTrue();
        assertThat(PerfCommissionService.sameStageSignature(
                "required:100|c:broken", "required:100|c:also-broken")).isFalse();
    }

    @Test
    void moneyConvertsCentsToYuanWithTwoDecimals() {
        assertThat(PerfCommissionExportService.moneyYuan(0)).isEqualTo("0.00");
        assertThat(PerfCommissionExportService.moneyYuan(5)).isEqualTo("0.05");
        assertThat(PerfCommissionExportService.moneyYuan(123456)).isEqualTo("1234.56");
        assertThat(PerfCommissionExportService.moneyYuan(-100)).isEqualTo("-1.00");
        assertThat(PerfCommissionExportService.moneyValue(123456)).isEqualByComparingTo(new BigDecimal("1234.56"));
    }

    @Test
    void textCellsGetFormulaInjectionPrefix() {
        assertThat(PerfCommissionExportService.csvRow(List.of("=SUM(A1:A2)")))
                .isEqualTo("\"'=SUM(A1:A2)\"\r\n");
        assertThat(PerfCommissionExportService.csvRow(List.of("+86")))
                .isEqualTo("\"'+86\"\r\n");
        assertThat(PerfCommissionExportService.csvRow(List.of("-1"))).isEqualTo("\"'-1\"\r\n");
        assertThat(PerfCommissionExportService.csvRow(List.of("@cmd"))).isEqualTo("\"'@cmd\"\r\n");
    }

    @Test
    void amountCellsAreNumbersAndNeverPrefixed() {
        // 金额列走 BigDecimal（Number），负值也原样输出；否则 -100.00 会被文本化成 '-100.00
        String line = PerfCommissionExportService.csvRow(new ArrayList<>(List.of(
                PerfCommissionExportService.moneyValue(-10000),
                PerfCommissionExportService.moneyValue(0))));
        assertThat(line).isEqualTo("\"-100.00\",\"0.00\"\r\n");
    }

    @Test
    void snowflakeEmployeeIdIsSpreadsheetTextWhileMoneyRemainsNumeric() {
        String line = PerfCommissionExportService.csvRow(List.of(
                PerfCommissionExportService.spreadsheetIdentifier(9_223_372_036_854_775_000L),
                PerfCommissionExportService.moneyValue(12_345)));

        assertThat(line).isEqualTo("\"'9223372036854775000\",\"123.45\"\r\n");
        assertThat(line).doesNotContain("=\"");
    }

    @Test
    void csvEscapesQuotesAndEmbeddedNewlines() {
        assertThat(PerfCommissionExportService.csvRow(List.of("张\"三")))
                .isEqualTo("\"张\"\"三\"\r\n");
        // 字段内部换行原样保留在引号内（合法 CSV 多行单元格），首字符不是公式前缀则不额外加单引号
        assertThat(PerfCommissionExportService.csvRow(List.of("换行\r\n姓名")))
                .isEqualTo("\"换行\r\n姓名\"\r\n");
        // 首字符为制表符/回车/换行同样视为公式注入风险
        assertThat(PerfCommissionExportService.csvRow(List.of("\t姓名")))
                .isEqualTo("\"'\t姓名\"\r\n");
    }

    @Test
    void nullCellBecomesEmptyQuotedField() {
        assertThat(PerfCommissionExportService.csvRow(Arrays.asList(null, 1L, "x")))
                .isEqualTo("\"\",\"1\",\"x\"\r\n");
    }
}
