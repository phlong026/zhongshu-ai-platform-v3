package cn.iocoder.yudao.designprovider;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Base64;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies that migrated PG and reachable Redis cannot bypass service-cost readiness. */
class DesignProviderBootProbeTest {

    @Test
    void migratedDatabaseAndRedisStillFailClosedWithoutServiceCostPrices() throws Exception {
        try (var postgres = cn.iocoder.yudao.module.platform.test.TestPostgres.database(DockerImageName.parse("postgres:16.15-alpine"))
                .withDatabaseName("design_provider").withUsername("owner").withPassword("test-password");
             var redis = cn.iocoder.yudao.module.platform.test.TestNativeService.container(DockerImageName.parse("redis:7.4.11-alpine")).withExposedPorts(6379)
                     .withCommand("redis-server", "--user", "default", "off", "--user", "design_provider_app",
                             "on", ">redis-test-password", "~*", "&*", "+@all")) {
            postgres.start();
            redis.start();
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration/platform", "classpath:db/migration/identity", "classpath:db/migration/design",
                            "classpath:db/migration/commerce", "classpath:db/migration/ai-orchestration",
                            "classpath:db/migration/design-capability", "classpath:db/migration/design-provider")
                    .baselineOnMigrate(false).outOfOrder(false).cleanDisabled(true).load().migrate();
            var application = new SpringApplication(DesignProviderApplication.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            assertThatThrownBy(() -> application.run(
                    "--spring.datasource.dynamic.datasource.master.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.dynamic.datasource.master.username=" + postgres.getUsername(),
                    "--spring.datasource.dynamic.datasource.master.password=" + postgres.getPassword(),
                    "--spring.data.redis.host=" + redis.getHost(),
                    "--spring.data.redis.port=" + redis.getMappedPort(6379),
                    "--spring.data.redis.password=redis-test-password",
                    "--zhongshu.design.asset.storage.provider=local",
                    "--zhongshu.design.asset.moderation.provider=stub",
                    "--zhongshu.design.capability.keys.test.caller-app-id=platform-b-test",
                    "--zhongshu.design.capability.keys.test.secret-base64=" + Base64.getEncoder().encodeToString(new byte[32]),
                    "--zhongshu.design.capability.keys.test.operations=CREATE_PROJECT"))
                    .hasStackTraceContaining("SERVICE_COST_PRICE_NOT_CONFIGURED:FLAT:2K");

            // Test-only cost fixtures prove the next closure boundary; they are not production defaults.
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.prepareStatement("INSERT INTO platform_cost_price"
                         + "(product,version,unit,prompt_micros,image_micros,usage_micros) VALUES (?,1,'MICRO_CNY',1,1,1)")) {
                for (String product : new String[]{"FLAT:2K", "FLAT:4K", "ELEVATION:2K", "ELEVATION:4K",
                        "BUDGET_ESTIMATE", "NOTE_POLISH"}) {
                    statement.setString(1, product);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            assertThatThrownBy(() -> application.run(
                    "--spring.datasource.dynamic.datasource.master.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.dynamic.datasource.master.username=" + postgres.getUsername(),
                    "--spring.datasource.dynamic.datasource.master.password=" + postgres.getPassword(),
                    "--spring.data.redis.host=" + redis.getHost(),
                    "--spring.data.redis.port=" + redis.getMappedPort(6379),
                    "--spring.data.redis.password=redis-test-password",
                    "--zhongshu.design.asset.storage.provider=local",
                    "--zhongshu.design.asset.moderation.provider=stub",
                    "--zhongshu.design.capability.keys.test.caller-app-id=platform-b-test",
                    "--zhongshu.design.capability.keys.test.secret-base64=" + Base64.getEncoder().encodeToString(new byte[32]),
                    "--zhongshu.design.capability.keys.test.operations=CREATE_PROJECT"))
                    .hasStackTraceContaining("AI 优化暂未配置");
        }
    }
}
