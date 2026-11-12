package cn.iocoder.yudao.module.platform.wallet;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WalletRecoveryWiringTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Dependencies.class, RefundPaymentService.class, ManualPointAdjustmentService.class,
                    WalletRecoveryJob.class, VirtualPaymentAdapter.class);

    @Test
    void defaultModeDoesNotInstallIndependentWalletBeans() {
        runner.withPropertyValues("platform.wallet.payment.enabled=true").run(WalletRecoveryWiringTest::assertAbsent);
    }

    @Test
    void legacyModeDoesNotInstallIndependentWalletBeansEvenWhenPaymentEnabled() {
        runner.withPropertyValues("platform.legacy-design-bridge.enabled=true", "platform.wallet.payment.enabled=true")
                .run(WalletRecoveryWiringTest::assertAbsent);
    }

    @Test
    void independentModeInstallsRecoveryButPreservesDisabledPaymentGate() {
        runner.withPropertyValues("platform.legacy-design-bridge.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(RefundPaymentService.class)
                    .hasSingleBean(ManualPointAdjustmentService.class).hasSingleBean(WalletRecoveryJob.class)
                    .doesNotHaveBean(VirtualPaymentAdapter.class);
        });
    }

    @Test
    void bothIndependentModeAndPaymentGateAreRequiredForAdapter() {
        runner.withPropertyValues("platform.legacy-design-bridge.enabled=false", "platform.wallet.payment.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(VirtualPaymentAdapter.class));
    }

    private static void assertAbsent(org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {
        assertThat(context).hasNotFailed().doesNotHaveBean(RefundPaymentService.class)
                .doesNotHaveBean(ManualPointAdjustmentService.class).doesNotHaveBean(WalletRecoveryJob.class)
                .doesNotHaveBean(VirtualPaymentAdapter.class);
    }

    @Configuration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean DataSource source() { return mock(DataSource.class); }
        @Bean PlatformTransactionManager manager() { return mock(PlatformTransactionManager.class); }
        @Bean WalletScope scope() { return mock(WalletScope.class); }
        @Bean PointAccountService points() { return mock(PointAccountService.class); }
        @Bean RechargePaymentService payment() { return mock(RechargePaymentService.class); }
        @Bean PaymentFactValidator validator() { return new PaymentFactValidator(); }
        @Bean WechatXPayProperties properties() {
            var properties = new WechatXPayProperties();
            properties.setAppid("fixture-app");
            return properties;
        }
    }
}
