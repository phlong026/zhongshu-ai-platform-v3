package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.module.design.budget.BudgetCalculator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 客户报价单汇总：工程小计不含保险，保险单列，管理费按工程小计 3%。 */
class BudgetQuoteSummaryTest {

    @Test
    void separatesInsuranceAndManagementFeeFromConstructionSubtotal() {
        var result = new BudgetCalculator.Result("COMPLETE", List.of(), List.of(
                new BudgetCalculator.ItemTotal("1", "STRUCTURE", "BODY", "主体结构", "STANDARD", "COMPLETE",
                        1_000_000L, 1_000_000L, List.of()),
                new BudgetCalculator.ItemTotal("2", "INSURANCE", "EXTERIOR", "保险", "STANDARD", "COMPLETE",
                        500_000L, 500_000L, List.of())
        ), Map.of("BODY", 1_000_000L, "EXTERIOR", 500_000L), 1_500_000L, 1_500_000L,
                List.of(), List.of(), List.of());

        var quote = BudgetCalculator.quoteSummary(result);
        assertThat(quote.bodySubtotalCents()).isEqualTo(1_000_000L);
        assertThat(quote.exteriorSubtotalCents()).isZero();
        assertThat(quote.constructionSubtotalCents()).isEqualTo(1_000_000L);
        assertThat(quote.insuranceCents()).isEqualTo(500_000L);
        assertThat(quote.managementFeeCents()).isEqualTo(30_000L);
        assertThat(quote.grandTotalCents()).isEqualTo(1_530_000L);
        assertThat(quote.managementFeeRate()).isEqualByComparingTo("0.03");
    }
}
