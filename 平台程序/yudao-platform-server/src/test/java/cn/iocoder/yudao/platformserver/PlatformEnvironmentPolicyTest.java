package cn.iocoder.yudao.platformserver;

import cn.iocoder.yudao.platformserver.wiring.PlatformEnvironmentPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformEnvironmentPolicyTest {

    @Test
    void acceptsPlatformConfigurationWithoutAnyDesignSecrets() {
        assertThatCode(() -> PlatformEnvironmentPolicy.validate(runtime(), false)).doesNotThrowAnyException();
    }

    @Test
    void rejectsLegacyBridgeEvenIfOtherConfigurationIsValid() {
        var env = runtime().withProperty("platform.legacy-design-bridge.enabled", "true");
        assertThatThrownBy(() -> PlatformEnvironmentPolicy.validate(env, false))
                .hasMessageContaining("platform.legacy-design-bridge.enabled");
    }

    @Test
    void rejectsSchemaMutationAtApiStartup() {
        for (String key : new String[]{"spring.flyway.enabled", "spring.sql.init.mode", "spring.jpa.hibernate.ddl-auto"}) {
            var env = runtime().withProperty(key, "true");
            assertThatThrownBy(() -> PlatformEnvironmentPolicy.validate(env, false)).hasMessageContaining(key);
        }
    }

    @Test
    void rejectsDesignDatabaseAndNeverPrintsPasswords() {
        var env = runtime().withProperty("spring.datasource.dynamic.datasource.master.url",
                "jdbc:postgresql://localhost:5432/zhongshu_design")
                .withProperty("spring.datasource.dynamic.datasource.master.password", "never-print-me");
        assertThatThrownBy(() -> PlatformEnvironmentPolicy.validate(env, false))
                .hasMessageContaining("zhongshu_platform_b").hasMessageNotContaining("never-print-me");
    }

    @Test
    void databaseUrlsCannotOverrideConnectionIdentityOrCarryCredentials() {
        for (String suffix : new String[]{"?user=platform_owner", "?password=secret-never-print",
                "?PGDBNAME=zhongshu_design", "?dbname=zhongshu_design", "?PGHOST=other-database",
                "?host=other-database", "?PGPORT=5433", "?port=5433", "?service=other-service",
                "?options=-c%20role%3Dplatform_owner", "?%75ser=platform_owner", "#other-database"}) {
            String url = "jdbc:postgresql://localhost:5432/zhongshu_platform_b" + suffix;
            var api = runtime().withProperty("spring.datasource.dynamic.datasource.master.url", url);
            assertThatThrownBy(() -> PlatformEnvironmentPolicy.validate(api, false))
                    .hasMessageContaining("spring.datasource.dynamic.datasource.master.url")
                    .hasMessageNotContaining("secret-never-print").hasNoCause();
            var maintenance = common();
            maintenance.setActiveProfiles("pg", "migration");
            maintenance.withProperty("spring.flyway.url", url)
                    .withProperty("spring.flyway.user", "platform_owner")
                    .withProperty("spring.flyway.password", "migration-secret");
            assertThatThrownBy(() -> PlatformEnvironmentPolicy.validate(maintenance, true))
                    .hasMessageContaining("spring.flyway.url").hasMessageNotContaining("secret-never-print").hasNoCause();
        }
    }

    @Test
    void databaseUrlAllowsOrdinaryConnectionOptions() {
        var env = runtime().withProperty("spring.datasource.dynamic.datasource.master.url",
                "jdbc:postgresql://localhost:5432/zhongshu_platform_b?connectTimeout=10&socketTimeout=30&sslmode=require&currentSchema=public");
        assertThatCode(() -> PlatformEnvironmentPolicy.validate(env, false)).doesNotThrowAnyException();
    }

    @Test
    void productionCannotEnableDevelopmentProfilesOrProviders() {
        var env = runtime();
        env.setActiveProfiles("pg", "prod", "local");
        assertThatThrownBy(() -> PlatformEnvironmentPolicy.validate(env, false)).hasMessageContaining("local");
        var invalid = runtime().withProperty("platform.identity.wechat.provider", "stub");
        assertThatThrownBy(() -> PlatformEnvironmentPolicy.validate(invalid, false))
                .hasMessageContaining("platform.identity.wechat.provider");
    }

    @Test
    void integrationMayDisableSmsWithoutWeakeningOtherProviders() {
        var env = runtime();
        env.setActiveProfiles("pg", "integration");
        env.withProperty("platform.identity.sms.provider", "disabled");
        assertThatCode(() -> PlatformEnvironmentPolicy.validate(env, false)).doesNotThrowAnyException();

        env.withProperty("platform.identity.wechat.provider", "stub");
        assertThatThrownBy(() -> PlatformEnvironmentPolicy.validate(env, false))
                .hasMessageContaining("platform.identity.wechat.provider");
    }

    @Test
    void integrationAlwaysRejectsSecurityMock() {
        var env = runtime();
        env.setActiveProfiles("pg", "integration");
        env.withProperty("platform.identity.sms.provider", "disabled")
                .withProperty("yudao.security.mock-enable", "true");

        assertThatThrownBy(() -> PlatformEnvironmentPolicy.validate(env, false))
                .hasMessageContaining("yudao.security.mock-enable");
    }

    @Test
    void smsDisabledIsRejectedOutsideIntegrationAndInProduction() {
        var withoutIntegration = runtime();
        withoutIntegration.setActiveProfiles("pg");
        withoutIntegration.withProperty("platform.identity.sms.provider", "disabled");
        assertThatThrownBy(() -> PlatformEnvironmentPolicy.validate(withoutIntegration, false))
                .hasMessageContaining("platform.identity.sms.provider");

        var withDevelopmentProfile = runtime();
        withDevelopmentProfile.setActiveProfiles("pg", "integration", "local");
        withDevelopmentProfile.withProperty("platform.identity.sms.provider", "disabled");
        assertThatThrownBy(() -> PlatformEnvironmentPolicy.validate(withDevelopmentProfile, false))
                .hasMessageContaining("platform.identity.sms.provider");

        for (String productionProfile : new String[]{"prod", "production"}) {
            var production = runtime();
            production.setActiveProfiles("pg", "integration", productionProfile);
            production.withProperty("platform.identity.sms.provider", "disabled");
            assertThatThrownBy(() -> PlatformEnvironmentPolicy.validate(production, false))
                    .hasMessageContaining("platform.identity.sms.provider");
        }

        var missingInProduction = runtime().withProperty("platform.identity.sms.provider", "");
        assertThatThrownBy(() -> PlatformEnvironmentPolicy.validate(missingInProduction, false))
                .hasMessageContaining("platform.identity.sms.provider");
    }

    @Test
    void migrationRequiresOwnerButDoesNotRequireRuntimeProviders() {
        var env = common();
        env.setActiveProfiles("pg", "migration");
        env.withProperty("spring.flyway.url", "jdbc:postgresql://localhost:5432/zhongshu_platform_b")
                .withProperty("spring.flyway.user", "platform_owner")
                .withProperty("spring.flyway.password", "migration-secret");
        assertThatCode(() -> PlatformEnvironmentPolicy.validate(env, true)).doesNotThrowAnyException();
        env.withProperty("spring.flyway.user", "platform_app");
        assertThatThrownBy(() -> PlatformEnvironmentPolicy.validate(env, true)).hasMessageContaining("platform_owner");
    }

    @Test
    void runtimeCannotMasqueradeAsMigrationUsingProfile() {
        var env = runtime();
        env.setActiveProfiles("pg", "migration");
        assertThatThrownBy(() -> PlatformEnvironmentPolicy.validate(env, false)).hasMessageContaining("migration");
    }

    @Test
    void migrationCannotAddDesignLocationsOrIgnoreMissingHistory() {
        var env = common();
        env.setActiveProfiles("pg", "migration");
        env.withProperty("spring.flyway.url", "jdbc:postgresql://localhost:5432/zhongshu_platform_b")
                .withProperty("spring.flyway.user", "platform_owner")
                .withProperty("spring.flyway.password", "migration-secret")
                .withProperty("spring.flyway.locations", "classpath:db/migration/design")
                .withProperty("spring.flyway.ignore-migration-patterns", "*:missing,*:future");
        assertThatThrownBy(() -> PlatformEnvironmentPolicy.validate(env, true))
                .hasMessageContaining("spring.flyway.locations")
                .hasMessageContaining("spring.flyway.ignore-migration-patterns");
    }

    @Test
    void productionAllowsUnusedMpButRequiresEmployeeMiniappSecrets() {
        var env = runtime().withProperty("wx.mp.app-id", "").withProperty("wx.mp.secret", "");
        assertThatCode(() -> PlatformEnvironmentPolicy.validate(env, false)).doesNotThrowAnyException();
        env.withProperty("wx.miniapp.secret", "");
        assertThatThrownBy(() -> PlatformEnvironmentPolicy.validate(env, false))
                .hasMessageContaining("wx.miniapp.secret");
    }

    @Test
    void malformedBindingsAndUnresolvedSecretsNeverExposeValuesOrCauses() {
        var unresolved = runtime().withProperty("spring.datasource.dynamic.datasource.master.password",
                "${secret-never-print}");
        assertThatThrownBy(() -> PlatformEnvironmentPolicy.validate(unresolved, false))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("secret-never-print").hasNoCause();
        var malformed = runtime().withProperty("platform.identity.wechat.apps", "secret-never-print");
        assertThatThrownBy(() -> PlatformEnvironmentPolicy.validate(malformed, false))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("secret-never-print").hasNoCause();
    }

    private MockEnvironment common() {
        return new MockEnvironment()
                .withProperty("platform.legacy-design-bridge.enabled", "false")
                .withProperty("spring.flyway.locations", String.join(",", PlatformEnvironmentPolicy.MIGRATION_LOCATIONS))
                .withProperty("spring.flyway.baseline-on-migrate", "false")
                .withProperty("spring.flyway.clean-disabled", "true")
                .withProperty("spring.flyway.validate-on-migrate", "true");
    }

    private MockEnvironment runtime() {
        var env = common();
        env.setActiveProfiles("pg", "prod");
        env.withProperty("spring.flyway.enabled", "false")
                .withProperty("spring.sql.init.mode", "never")
                .withProperty("spring.jpa.hibernate.ddl-auto", "none")
                .withProperty("spring.quartz.jdbc.initialize-schema", "never")
                .withProperty("spring.datasource.dynamic.datasource.master.url", "jdbc:postgresql://localhost:5432/zhongshu_platform_b")
                .withProperty("spring.datasource.dynamic.datasource.master.username", "platform_app")
                .withProperty("spring.datasource.dynamic.datasource.master.password", "database-secret")
                .withProperty("spring.data.redis.host", "redis-b")
                .withProperty("spring.data.redis.username", "platform_app")
                .withProperty("spring.data.redis.password", "redis-secret")
                .withProperty("platform.identity.wechat.provider", "real")
                .withProperty("platform.identity.wechat.apps[0].appid", "platform-app")
                .withProperty("platform.identity.wechat.apps[0].secret", "wechat-secret")
                .withProperty("wx.miniapp.appid", "platform-miniapp")
                .withProperty("wx.miniapp.secret", "miniapp-secret")
                .withProperty("platform.identity.sms.provider", "real")
                .withProperty("platform.identity.sms.sdk-app-id", "sms-app")
                .withProperty("platform.identity.sms.sign-name", "platform")
                .withProperty("platform.identity.sms.template-id", "template")
                .withProperty("platform.identity.sms.secret-id", "sms-id")
                .withProperty("platform.identity.sms.secret-key", "sms-secret")
                .withProperty("platform.identity.sms.region", "ap-guangzhou")
                .withProperty("zhongshu.franchise.asset.storage.provider", "cos")
                .withProperty("zhongshu.franchise.asset.storage.cos.endpoint", "https://cos.example.test")
                .withProperty("zhongshu.franchise.asset.storage.cos.region", "test-region")
                .withProperty("zhongshu.franchise.asset.storage.cos.bucket", "b-bucket")
                .withProperty("zhongshu.franchise.asset.storage.cos.secret-id", "asset-id")
                .withProperty("zhongshu.franchise.asset.storage.cos.secret-key", "asset-secret")
                .withProperty("zhongshu.content.provider", "real")
                .withProperty("zhongshu.content.task-sweep-enabled", "true")
                .withProperty("yudao.security.mock-enable", "false")
                .withProperty("spring.datasource.druid.stat-view-servlet.enabled", "false")
                .withProperty("management.endpoint.env.show-values", "NEVER")
                .withProperty("management.endpoint.configprops.show-values", "NEVER")
                .withProperty("management.endpoint.health.show-details", "never")
                .withProperty("management.endpoint.health.show-components", "never")
                .withProperty("management.endpoints.web.exposure.include", "health,info");
        return env;
    }
}
