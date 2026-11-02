package cn.iocoder.yudao.module.franchise.performance.content;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PerfMetrics 纯函数口径（19 §七单测清单：指标口径/adjustments 生效/目标三态/幂等重放——纯 Fake）。
 */
class PerfMetricsTest {

    private static PerfMetrics.SourceRow row(String key, String type, long value, Long userId) {
        return new PerfMetrics.SourceRow(key, type, value, userId, LocalDate.of(2026, 11, 2),
                key.startsWith("fin-receipt:") ? key : null);
    }

    @Test
    void taskValueSumsByTypeAndUser() {
        List<PerfMetrics.SourceRow> rows = List.of(
                row("r1", "auto:collectionsAmount", 40000000L, 10L),
                row("r2", "auto:collectionsAmount", 20000000L, 10L),
                row("r3", "auto:collectionsAmount", 999L, 20L),
                row("n1", "auto:completedNodes", 100L, 10L),
                row("n2", "auto:completedNodes", 100L, 10L));
        assertThat(PerfMetrics.taskValue(rows, "auto:collectionsAmount", 10L)).isEqualTo(60000000L);
        assertThat(PerfMetrics.taskValue(rows, "auto:collectionsAmount", 20L)).isEqualTo(999L);
        assertThat(PerfMetrics.taskValue(rows, "auto:collectionsAmount", null)).isEqualTo(60000999L);
        // P1-9 定稿：数量类 ×100 存储——200 存储 = 2 个
        assertThat(PerfMetrics.taskValue(rows, "auto:completedNodes", 10L)).isEqualTo(200L);
        assertThat(0).isZero(); // 展示层换算归 v3 适配层（displayValue），后端只存原始 ×100
    }

    @Test
    void adjustmentsExcludeAndReassign() {
        // r1 业务事实 id=fin-receipt:100——派生行（回款金额+登记笔数）共享 businessId，一次裁决全部覆盖（P1-8）
        PerfMetrics.SourceRow r1 = new PerfMetrics.SourceRow("fin-receipt:100", "auto:collectionsAmount",
                100L, 10L, LocalDate.of(2026, 11, 2), "fin-receipt:100");
        PerfMetrics.SourceRow r1reg = new PerfMetrics.SourceRow("fin-reg-receipt:100", "auto:financeRegistrations",
                100L, 10L, LocalDate.of(2026, 11, 2), "fin-receipt:100");
        PerfMetrics.SourceRow r2 = new PerfMetrics.SourceRow("fin-receipt:101", "auto:collectionsAmount",
                50L, 10L, LocalDate.of(2026, 11, 2), "fin-receipt:101");
        List<PerfMetrics.SourceRow> rows = List.of(r1, r1reg, r2);
        // 剔除 fin-receipt:100 → 回款金额与登记笔数两派生行同时剔除
        List<PerfMetrics.SourceRow> excluded = PerfMetrics.applyAdjustments(rows,
                List.of(new PerfMetrics.Adjustment("fin-receipt:100", null, true)));
        assertThat(PerfMetrics.taskValue(excluded, "auto:collectionsAmount", null)).isEqualTo(50L);
        assertThat(PerfMetrics.taskValue(excluded, "auto:financeRegistrations", null)).isZero();
        // 改归属 fin-receipt:100 → 20
        List<PerfMetrics.SourceRow> reassigned = PerfMetrics.applyAdjustments(rows,
                List.of(new PerfMetrics.Adjustment("fin-receipt:100", 20L, false)));
        assertThat(PerfMetrics.taskValue(reassigned, "auto:collectionsAmount", 10L)).isEqualTo(50L);
        assertThat(PerfMetrics.taskValue(reassigned, "auto:collectionsAmount", 20L)).isEqualTo(100L);
        // 未命中的 adjustment 不影响
        assertThat(PerfMetrics.applyAdjustments(rows,
                List.of(new PerfMetrics.Adjustment("fin-receipt:999", 30L, true)))).hasSize(3);
    }

    @Test
    void departmentMetricsGroupsAndDerives() {
        List<PerfMetrics.SourceRow> rows = List.of(
                row("c1", "metric:commissionAmount", 68000000L, 2L),
                row("p1", "auto:publications", 100L, 2L),
                row("l1", "auto:effectiveLeads", 100L, 2L),
                row("n1", "auto:completedNodes", 100L, 5L),
                row("m1", "auto:qualifiedDeliveries", 100L, 5L),
                row("f1", "auto:financeRegistrations", 100L, 4L));
        var byDept = PerfMetrics.departmentMetrics(rows);
        assertThat(byDept.get("business")).containsEntry("metric:commissionAmount", 68000000L)
                .containsEntry("metric:activityTotal", 200L); // 派生=publications+effectiveLeads（×100）
        assertThat(byDept.get("engineering")).containsEntry("auto:completedNodes", 100L)
                .containsEntry("auto:qualifiedDeliveries", 100L)
                .containsEntry("metric:firstPassNodes", 100L); // §九 简化=completedNodes
        assertThat(byDept.get("finance")).containsEntry("auto:financeRegistrations", 100L);
    }

    @Test
    void sourceSignatureIsStableAndSorted() {
        List<PerfMetrics.SourceRow> a = List.of(row("k2", "t", 1L, 5L), row("k1", "t", 2L, null));
        List<PerfMetrics.SourceRow> b = List.of(row("k1", "t", 2L, null), row("k2", "t", 1L, 5L));
        assertThat(PerfMetrics.sourceSignature(a)).isEqualTo(PerfMetrics.sourceSignature(b));
        List<PerfMetrics.SourceRow> c = List.of(row("k1", "t", 3L, null), row("k2", "t", 1L, 5L));
        assertThat(PerfMetrics.sourceSignature(a)).isNotEqualTo(PerfMetrics.sourceSignature(c));
        // P1-12：SHA-256 定长（不随源行数线性膨胀）
        assertThat(PerfMetrics.sourceSignature(a)).hasSize(64);
    }

    @Test
    void periodHelpers() {
        assertThat(PerfMetrics.validPeriod("2026-11")).isTrue();
        assertThat(PerfMetrics.validPeriod("2026-13")).isFalse();
        assertThat(PerfMetrics.validPeriod("2026-1")).isFalse();
        assertThat(PerfMetrics.validPeriod(null)).isFalse();
        assertThat(PerfMetrics.previousPeriod("2026-11")).isEqualTo("2026-10");
        assertThat(PerfMetrics.previousPeriod("2026-01")).isEqualTo("2025-12");
    }
}
