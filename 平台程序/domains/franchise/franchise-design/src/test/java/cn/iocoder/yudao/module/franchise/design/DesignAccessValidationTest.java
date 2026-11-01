package cn.iocoder.yudao.module.franchise.design;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static cn.iocoder.yudao.module.franchise.api.FranchiseDesignErrorCodes.SCHEME_SIZE_REQUIRED;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B2 板块 A / P-8 示范单测①：BUDGET 回写尺寸校验（1_112_000_001）与 kind 分支边界。
 * 纯函数测试（无 Spring 上下文）；服务账号并发唯一性由 e2e AC3 真 PG 覆盖。
 */
class DesignAccessValidationTest {

    @Test
    void 合法尺寸通过() {
        assertThatCode(() -> DesignAccessService.validateBudgetDimensions(
                new BigDecimal("8.5"), new BigDecimal("10"), 2, new BigDecimal("170")))
                .doesNotThrowAnyException();
        assertThatCode(() -> DesignAccessService.validateBudgetDimensions(
                new BigDecimal("999.99"), new BigDecimal("999.99"), 20, new BigDecimal("99999.99")))
                .doesNotThrowAnyException();
    }

    @Test
    void 缺尺寸拒绝() {
        assertThatThrownBy(() -> DesignAccessService.validateBudgetDimensions(
                null, new BigDecimal("10"), 2, new BigDecimal("170")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("面宽");
    }

    @Test
    void 超范围拒绝() {
        // 宽 1000 超上限
        assertThatThrownBy(() -> DesignAccessService.validateBudgetDimensions(
                new BigDecimal("1000"), new BigDecimal("10"), 2, new BigDecimal("170")))
                .isInstanceOf(ServiceException.class);
        // 层数 0
        assertThatThrownBy(() -> DesignAccessService.validateBudgetDimensions(
                new BigDecimal("8"), new BigDecimal("10"), 0, new BigDecimal("170")))
                .isInstanceOf(ServiceException.class);
        // 层数 21
        assertThatThrownBy(() -> DesignAccessService.validateBudgetDimensions(
                new BigDecimal("8"), new BigDecimal("10"), 21, new BigDecimal("170")))
                .isInstanceOf(ServiceException.class);
        // 面积 100000 超上限
        assertThatThrownBy(() -> DesignAccessService.validateBudgetDimensions(
                new BigDecimal("8"), new BigDecimal("10"), 2, new BigDecimal("100000")))
                .isInstanceOf(ServiceException.class);
        // 三位小数
        assertThatThrownBy(() -> DesignAccessService.validateBudgetDimensions(
                new BigDecimal("8.505"), new BigDecimal("10"), 2, new BigDecimal("170")))
                .isInstanceOf(ServiceException.class);
        // 零/负面积
        assertThatThrownBy(() -> DesignAccessService.validateBudgetDimensions(
                new BigDecimal("8"), new BigDecimal("10"), 2, BigDecimal.ZERO))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void 错误码为1122000001() {
        assertThatThrownBy(() -> DesignAccessService.validateBudgetDimensions(
                null, null, null, null))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.getCode())
                                .isEqualTo(SCHEME_SIZE_REQUIRED.getCode()));
    }
}
