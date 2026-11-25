package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.ratelimiter.core.VerifiedAccountRateLimiter;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.framework.ratelimiter.core.keyresolver.impl.ClientIpRateLimiterKeyResolver;
import cn.iocoder.yudao.module.design.budget.BudgetAccountPriceService;
import cn.iocoder.yudao.module.design.budget.BudgetCatalogService;
import cn.iocoder.yudao.module.design.controller.app.AppAccountPriceController;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 本人当地单价的写接口限流约定（2026-11-25）。
 *
 * <p>新建城市要逐项配置约 35 个价。旧配置为账号桶 10 次/60 秒（Redisson 令牌桶：突发 10 次后每 6 秒补 1 个）
 * 且 PUT 与 DELETE 共用，逐项配置到后半段会返回 429，前端表现为「保存失败」。这里锁死阈值、时间单位与桶名，
 * 防止后续被改回小值或把「恢复默认」重新并进改价桶。</p>
 */
class AppAccountPriceRateLimitTest {

    private static final long ACCOUNT_ID = 1L;
    private static final String SAVE_BUCKET = "budget-my-price";
    private static final String RESET_BUCKET = "budget-my-price-reset";

    private final BudgetAccountPriceService accountPrices = mock(BudgetAccountPriceService.class);
    private final BudgetCatalogService catalog = mock(BudgetCatalogService.class);
    private final VerifiedAccountRateLimiter limiter = mock(VerifiedAccountRateLimiter.class);
    private final IdentitySessionPort identities = token -> "owner".equals(token)
            ? Optional.of(new IdentitySessionPort.SessionContext(ACCOUNT_ID, "test", "owner", false))
            : Optional.empty();

    private AppAccountPriceController controller() {
        var controller = new AppAccountPriceController();
        ReflectionTestUtils.setField(controller, "accountPriceService", accountPrices);
        ReflectionTestUtils.setField(controller, "budgetCatalogService", catalog);
        ReflectionTestUtils.setField(controller, "identitySessionPort", identities);
        ReflectionTestUtils.setField(controller, "verifiedAccountRateLimiter", limiter);
        return controller;
    }

    @Test
    @DisplayName("单项改价：账号桶 60 次/60 秒，IP 桶 120 次/60 秒")
    void setMyPriceAllowsConfiguringCityPricesItemByItem() throws Exception {
        controller().setMyPrice("208001", "u1-abc123", Map.of("unitPriceCents", 60_000), "Bearer owner");

        verify(limiter).check(SAVE_BUCKET, ACCOUNT_ID, 60, 60);
        RateLimiter annotation = annotation("setMyPrice", String.class, String.class, Map.class, String.class);
        assertThat(annotation.time()).isEqualTo(60);
        assertThat(annotation.timeUnit()).isEqualTo(TimeUnit.SECONDS);
        assertThat(annotation.count()).isEqualTo(120);
        assertThat(annotation.keyResolver()).isEqualTo(ClientIpRateLimiterKeyResolver.class);
    }

    @Test
    @DisplayName("恢复默认：独立桶 30 次/60 秒，不占用改价额度")
    void resetMyPriceUsesItsOwnBucket() throws Exception {
        controller().resetMyPrice("208001", "u1-abc123", "Bearer owner");

        verify(limiter).check(RESET_BUCKET, ACCOUNT_ID, 30, 60);
        verify(limiter, never()).check(eq(SAVE_BUCKET), anyLong(), anyInt(), anyInt());
        RateLimiter annotation = annotation("resetMyPrice", String.class, String.class, String.class);
        assertThat(annotation.count()).isEqualTo(120);
        assertThat(annotation.keyResolver()).isEqualTo(ClientIpRateLimiterKeyResolver.class);
    }

    @Test
    @DisplayName("两个写接口不共用限流桶")
    void writeEndpointsDoNotShareABucket() {
        assertThat(RESET_BUCKET).isNotEqualTo(SAVE_BUCKET);
    }

    private RateLimiter annotation(String name, Class<?>... parameterTypes) throws Exception {
        Method method = AppAccountPriceController.class.getMethod(name, parameterTypes);
        RateLimiter annotation = method.getAnnotation(RateLimiter.class);
        assertThat(annotation).as("@RateLimiter on " + name).isNotNull();
        return annotation;
    }
}
