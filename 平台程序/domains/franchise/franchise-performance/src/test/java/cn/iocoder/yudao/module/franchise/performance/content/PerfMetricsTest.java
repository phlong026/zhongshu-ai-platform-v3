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
        return new PerfMetrics.SourceRow(key, type, value, userId, LocalDate.of(2026, 11, 2));
    }

    @Test
    void taskValueSumsByTypeAndUser() {
        List<PerfMetrics.SourceRow> rows = List.of(
                row("r1", "auto:collectionsAmount", 40000000L, 10L),
                row("r2", "auto:collectionsAmount", 20000000L, 10L),
                row("r3", "auto:collectionsAmount", 999L, 20L),
                row("n1", "auto:completedNodes", 1L, 10L));
        assertThat(PerfMetrics.taskValue(rows, "auto:collectionsAmount", 10L)).isEqualTo(60000000L);
        assertThat(PerfMetrics.taskValue(rows, "auto:collectionsAmount", 20L)).isEqualTo(999L);
        assertThat(PerfMetrics.taskValue(rows, "auto:collectionsAmount", null)).isEqualTo(60000999L);
        assertThat(PerfMetrics.taskValue(rows, "auto:completedNodes", 10L)).isEqualTo(1L);
        assertThat(PerfMetrics.taskValue(rows, "auto:onTimeNodes", 10L)).isZero();
    }

    @Test
    void adjustmentsExcludeAndReassign() {
        List<PerfMetrics.SourceRow> rows = List.of(
                row("r1", "auto:collectionsAmount", 100L, 10L),
                row("r2", "auto:collectionsAmount", 50L, 10L));
        // 剔除 r2（P2-3：excluded）
        List<PerfMetrics.SourceRow> excluded = PerfMetrics.applyAdjustments(rows,
                List.of(new PerfMetrics.Adjustment("r2", null, true)));
        assertThat(PerfMetrics.taskValue(excluded, "auto:collectionsAmount", null)).isEqualTo(100L);
        // 改归属 r1→20
        List<PerfMetrics.SourceRow> reassigned = PerfMetrics.applyAdjustments(rows,
                List.of(new PerfMetrics.Adjustment("r1", 20L, false)));
        assertThat(PerfMetrics.taskValue(reassigned, "auto:collectionsAmount", 10L)).isEqualTo(50L);
        assertThat(PerfMetrics.taskValue(reassigned, "auto:collectionsAmount", 20L)).isEqualTo(100L);
        // 未命中的 adjustment 不影响
        assertThat(PerfMetrics.applyAdjustments(rows,
                List.of(new PerfMetrics.Adjustment("nope", 30L, true)))).hasSize(2);
    }

    @Test
    void departmentMetricsGroupsAndDerives() {
        List<PerfMetrics.SourceRow> rows = List.of(
                row("c1", "metric:commissionAmount", 68000000L, 2L),
                row("p1", "auto:publications", 1L, 2L),
                row("l1", "auto:effectiveLeads", 1L, 2L),
                row("n1", "auto:completedNodes", 1L, 5L),
                row("m1", "auto:qualifiedDeliveries", 1L, 5L),
                row("f1", "auto:financeRegistrations", 1L, 4L));
        var byDept = PerfMetrics.departmentMetrics(rows);
        assertThat(byDept.get("business")).containsEntry("metric:commissionAmount", 68000000L)
                .containsEntry("metric:activityTotal", 2L); // 派生=publications+effectiveLeads
        assertThat(byDept.get("engineering")).containsEntry("auto:completedNodes", 1L)
                .containsEntry("auto:qualifiedDeliveries", 1L)
                .containsEntry("metric:firstPassNodes", 1L); // §九 简化=completedNodes
        assertThat(byDept.get("finance")).containsEntry("auto:financeRegistrations", 1L);
    }

    @Test
    void sourceSignatureIsStableAndSorted() {
        List<PerfMetrics.SourceRow> a = List.of(row("k2", "t", 1L, 5L), row("k1", "t", 2L, null));
        List<PerfMetrics.SourceRow> b = List.of(row("k1", "t", 2L, null), row("k2", "t", 1L, 5L));
        assertThat(PerfMetrics.sourceSignature(a)).isEqualTo(PerfMetrics.sourceSignature(b));
        List<PerfMetrics.SourceRow> c = List.of(row("k1", "t", 3L, null), row("k2", "t", 1L, 5L));
        assertThat(PerfMetrics.sourceSignature(a)).isNotEqualTo(PerfMetrics.sourceSignature(c));
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
