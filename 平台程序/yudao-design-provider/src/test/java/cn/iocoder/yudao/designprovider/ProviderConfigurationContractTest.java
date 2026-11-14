package cn.iocoder.yudao.designprovider;

import cn.iocoder.yudao.module.design.capability.security.CapabilityProviderReadiness;
import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderConfigurationContractTest {

    @Test
    void requiredSecretGuardRejectsMissingPlaceholderAndBlankValues() {
        var placeholderEnvironment = environmentWith(
                "spring.datasource.dynamic.datasource.master.password", "${DP_PG_PASSWORD}");
        assertThatThrownBy(() -> ProviderRequiredSecretConfiguration.require(placeholderEnvironment,
                "spring.datasource.dynamic.datasource.master.password", "DP_PG_PASSWORD"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DP_PG_PASSWORD is required");

        var blankEnvironment = environmentWith("spring.data.redis.password", " ");
        assertThatThrownBy(() -> ProviderRequiredSecretConfiguration.require(blankEnvironment,
                "spring.data.redis.password", "DP_REDIS_PASSWORD"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DP_REDIS_PASSWORD is required");
    }

    private static StandardEnvironment environmentWith(String key, String value) {
        var environment = new StandardEnvironment();
        var properties = new Properties();
        properties.setProperty(key, value);
        environment.getPropertySources().addFirst(new PropertiesPropertySource("test", properties));
        return environment;
    }

    @Test
    void everyRuntimeAndMigrationPasswordIsRequiredWithoutArtifactDefault() throws Exception {
        String runtime = StreamUtils.copyToString(new ClassPathResource("application.properties").getInputStream(),
                StandardCharsets.UTF_8);
        String migration = StreamUtils.copyToString(new ClassPathResource("application-migration.properties").getInputStream(),
                StandardCharsets.UTF_8);
        assertThat(runtime).contains("password=${DP_PG_PASSWORD}", "password=${DP_REDIS_PASSWORD}")
                .doesNotContain("DP_PG_PASSWORD:", "DP_REDIS_PASSWORD:");
        assertThat(migration).contains("spring.flyway.password=${DP_MIGRATION_PASSWORD}")
                .doesNotContain("DP_MIGRATION_PASSWORD:");
    }

    @Test
    void capabilityReadinessRejectsAdmissionWithoutRealJobDriver() {
        var factory = new DefaultListableBeanFactory();
        var processor = ProviderDriverReadinessConfiguration.capabilityDriverReadiness(
                factory.getBeanProvider(ProviderRuntimeDriver.class));
        CapabilityProviderReadiness delegate = () -> { };
        var guarded = (CapabilityProviderReadiness) processor.postProcessAfterInitialization(delegate, "readiness");
        assertThatThrownBy(guarded::verifyReady)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DESIGN_PROVIDER_JOB_DRIVER_NOT_READY");
    }

    @Test
    void caseReferenceRemainsFailClosedUntilProviderAuthorizationIsImplemented() {
        var request = new DesignCapabilityContract.Request<>("platform-b-test", 1L,
                new DesignServicePort.Actor(2L, 3L, "DESIGNER"),
                DesignCapabilityContract.Operation.CREATE_PROJECT, "request-1", "nonce-1",
                new DesignServicePort.DesignProjectCreate("CASE_REFERENCE", 99L, null,
                        java.util.Map.of(), "line", "project-1"));

        assertThat(ProviderCapabilityBoundaryConfiguration.isBlockedCaseReference(request)).isTrue();
    }

    @Test
    void providerNotePolishHasNoDailyQuotaButKeepsMinuteAndConcurrencySafeguards() {
        var clock = new AtomicLong();
        var limiter = new ProviderNotePolishLimiter(10, 2, clock::get);
        for (int index = 0; index < 101; index++) {
            limiter.acquire(1L);
            limiter.release();
            if ((index + 1) % 10 == 0) clock.addAndGet(60_001L);
        }

        var minuteGuard = new ProviderNotePolishLimiter(1, 2, clock::get);
        minuteGuard.acquire(2L);
        minuteGuard.release();
        assertThatThrownBy(() -> minuteGuard.acquire(2L)).hasMessageContaining("操作太频繁");

        var concurrencyGuard = new ProviderNotePolishLimiter(10, 1, clock::get);
        concurrencyGuard.acquire(3L);
        assertThatThrownBy(() -> concurrencyGuard.acquire(4L)).hasMessageContaining("AI 优化繁忙");
        concurrencyGuard.release();
    }

    @Test
    void providerNotePolishCleanupAndSameAccountAdmissionAreAtomic() throws Exception {
        var clock = new AtomicLong();
        var limiter = new ProviderNotePolishLimiter(10, 64, clock::get, 1);
        for (long accountId = 1; accountId <= 20; accountId++) {
            limiter.acquire(accountId);
            limiter.release();
        }
        clock.addAndGet(60_001L);

        int workers = 64;
        var ready = new CountDownLatch(workers);
        var start = new CountDownLatch(1);
        var admitted = new AtomicInteger();
        var rateLimited = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(workers);
        try {
            for (int index = 0; index < workers; index++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        limiter.acquire(999L);
                        admitted.incrementAndGet();
                        limiter.release();
                    } catch (cn.iocoder.yudao.framework.common.exception.ServiceException expected) {
                        rateLimited.incrementAndGet();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(admitted).hasValue(10);
        assertThat(rateLimited).hasValue(workers - 10);
    }
}
