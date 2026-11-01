package cn.iocoder.yudao.module.franchise.sales;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.QUOTE_TOTAL_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B2 板块 B / P-8 示范单测：报价服务端计算纯函数（行金额/总额/溢出/伪造值覆盖）。
 * AC5/AC16 服务端计算与边界断言的纯函数层；并发与状态机由 e2e（真 PG）覆盖。
 */
class SaleQuoteMathTest {

    private Map<String, Object> row(String label, int quantity, long unitPriceCents) {
        Map<String, Object> row = new HashMap<>();
        row.put("label", label);
        row.put("quantity", quantity);
        row.put("unitPriceCents", unitPriceCents);
        row.put("valueCents", 12345L); // 客户端伪造值
        return row;
    }

    @Test
    void 服务端计算覆盖伪造行金额() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("主体", 10, 1000));
        long[] values = SaleQuoteMath.normalizeRows("main", rows);
        assertThat(values[0]).isEqualTo(10_000L);
        assertThat(rows.get(0).get("valueCents")).isEqualTo(10_000L); // 覆盖伪造的 12345
    }

    @Test
    void 边界合法值通过() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("上限", 1_000_000, 1_000_000_000L)); // quantity/单价各自上限，行金额=10^15 超限应拒绝
        assertThatThrownBy(() -> SaleQuoteMath.normalizeRows("main", rows))
                .isInstanceOf(ServiceException.class);
        // 单行恰好等于上限
        List<Map<String, Object>> ok = new ArrayList<>();
        ok.add(row("行", 1000, 1_000_000_000L)); // 10^12 恰好上限
        assertThat(SaleQuoteMath.normalizeRows("main", ok)[0]).isEqualTo(1_000_000_000_000L);
    }

    @Test
    void 非法结构拒绝() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("零数量", 0, 100));
        assertThatThrownBy(() -> SaleQuoteMath.normalizeRows("main", rows))
                .isInstanceOf(ServiceException.class)
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.getCode())
                                .isEqualTo(QUOTE_TOTAL_INVALID.getCode()));
        // 空标签
        List<Map<String, Object>> blank = new ArrayList<>();
        blank.add(row(" ", 1, 100));
        assertThatThrownBy(() -> SaleQuoteMath.normalizeRows("main", blank))
                .isInstanceOf(ServiceException.class);
        // 行数超限
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) tooMany.add(row("r" + i, 1, 1));
        assertThatThrownBy(() -> SaleQuoteMath.normalizeRows("main", tooMany))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void 乘法回绕防护() {
        List<Map<String, Object>> rows = new ArrayList<>();
        // quantity 在界内但乘积巨大——先撞单价上限 10^9，再超行上限
        rows.add(row("回绕", 1_000_000, 1_000_000_000L));
        assertThatThrownBy(() -> SaleQuoteMath.normalizeRows("main", rows))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void 总额计算与边界() {
        long total = SaleQuoteMath.totalOf(1_000_000, 500_000, 100_000, 0);
        assertThat(total).isEqualTo(1_600_000L);
        // 全 0 → 无效报价
        assertThatThrownBy(() -> SaleQuoteMath.totalOf(0, 0, 0, 0))
                .isInstanceOf(ServiceException.class);
        // 附加费为负拒绝
        assertThatThrownBy(() -> SaleQuoteMath.extraFee(-1L)).isInstanceOf(ServiceException.class);
        // 累加溢出拒绝
        assertThatThrownBy(() -> SaleQuoteMath.totalOf(SaleQuoteMath.MAX_AMOUNT, SaleQuoteMath.MAX_AMOUNT, 0, 0))
                .isInstanceOf(ServiceException.class);
    }
}
