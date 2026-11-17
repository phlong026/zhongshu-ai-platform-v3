package cn.iocoder.yudao.platformserver;

import cn.iocoder.yudao.platformmigration.PlatformMigrationConfiguration;
import cn.iocoder.yudao.module.franchise.design.publiccase.PublicCaseAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A real B database and Redis, with the entire application eagerly wired and no mocked business beans. */
class PlatformBootIntegrationTest {

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class TestRedisPool {
        @org.springframework.context.annotation.Bean
        org.redisson.spring.starter.RedissonAutoConfigurationCustomizer smallIntegrationPool() {
            // Real authenticated Redis is sufficient with a small pool in a single-client boot test.
            return config -> {
                config.setThreads(2).setNettyThreads(2);
                config.useSingleServer().setConnectionMinimumIdleSize(1).setConnectionPoolSize(4)
                        .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2);
            };
        }
    }

    @TempDir
    Path temporaryDirectory;

    @Test
    void migrationAndApiHaveSeparateCredentialsAndRuntimeBoundaries() throws Exception {
        try (var pg = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.15-alpine"))
                .withDatabaseName("zhongshu_platform_b").withUsername("platform_owner").withPassword("owner-test-password");
             var redis = new GenericContainer<>(DockerImageName.parse("redis:7.4.11-alpine"))
                     .withExposedPorts(6379)
                     .withCommand("redis-server", "--user", "default", "off", "--user", "platform_app", "on",
                             ">redis-test-password", "~*", "&*", "+@all")) {
            pg.start();
            redis.start();
            SpringApplication migration = new SpringApplication(PlatformMigrationConfiguration.class);
            migration.setWebApplicationType(WebApplicationType.NONE);
            migration.setAdditionalProfiles("pg", "migration");
            try (var context = migration.run(
                    externalConfiguration(true),
                    "--spring.flyway.url=" + pg.getJdbcUrl(),
                    "--spring.flyway.user=platform_owner",
                    "--spring.flyway.password=owner-test-password")) {
                for (String runtimeBean : List.of("contentTaskJob", "requestMappingHandlerMapping",
                        "redisConnectionFactory", "orgOnboardService", "quartzScheduler")) {
                    assertThat(context.containsBean(runtimeBean)).as("maintenance must not create %s", runtimeBean).isFalse();
                }
            }
            try (var connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("CREATE ROLE platform_app LOGIN PASSWORD 'app-test-password'");
                statement.execute("GRANT CONNECT ON DATABASE zhongshu_platform_b TO platform_app");
                statement.execute("GRANT USAGE ON SCHEMA public TO platform_app");
                statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO platform_app");
                statement.execute("GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO platform_app");
                try (var result = statement.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' "
                        + "AND table_name IN ('account','asset','ai_job','design_project','design_point_ledger','recharge_order')")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isZero();
                }
            }
            Path usernameFile = Files.writeString(temporaryDirectory.resolve("admin-username"), "platformadmin");
            Path passwordFile = Files.writeString(temporaryDirectory.resolve("admin-password"), "TestOnly#852963");
            YudaoPlatformServerApplication.main(new String[]{
                    "bootstrap-admin", "--spring.profiles.active=pg,migration", externalConfiguration(true),
                    "--spring.flyway.url=" + pg.getJdbcUrl(), "--spring.flyway.user=platform_owner",
                    "--spring.flyway.password=owner-test-password",
                    "--platform.bootstrap.admin-username-file=" + usernameFile,
                    "--platform.bootstrap.admin-password-file=" + passwordFile});
            SpringApplication api = new SpringApplication(YudaoPlatformServerApplication.class, TestRedisPool.class);
            api.setAdditionalProfiles("pg", "local");
            List<String> options = new ArrayList<>(List.of(
                    externalConfiguration(false),
                    "--server.port=0",
                    "--spring.datasource.dynamic.datasource.master.url=" + pg.getJdbcUrl(),
                    "--spring.datasource.dynamic.datasource.master.username=platform_app",
                    "--spring.datasource.dynamic.datasource.master.password=app-test-password",
                    "--spring.data.redis.host=" + redis.getHost(),
                    "--spring.data.redis.port=" + redis.getMappedPort(6379),
                    "--spring.data.redis.username=platform_app",
                    "--spring.data.redis.password=redis-test-password",
                    "--platform.identity.wechat.provider=stub",
                    "--platform.identity.sms.provider=stub",
                    "--yudao.captcha.enable=false",
                    "--wx.miniapp.appid=platform-miniapp-test",
                    "--wx.miniapp.secret=platform-miniapp-test-secret",
                    "--zhongshu.org-point.headquarters-tenant-id=1",
                    "--zhongshu.design.remote.enabled=true",
                    "--zhongshu.design.remote.origin=https://design.example.test",
                    "--zhongshu.design.remote.caller-app-id=platform-b-test",
                    "--zhongshu.design.remote.active-key-id=test-key",
                    "--zhongshu.design.remote.keys.test-key=" + java.util.Base64.getEncoder().encodeToString(new byte[32]),
                    "--zhongshu.franchise.asset.storage.provider=local",
                    "--zhongshu.franchise.asset.storage-root=" + temporaryDirectory.resolve("assets"),
                    "--zhongshu.content.provider=real",
                    "--zhongshu.content.task-sweep-enabled=false"));
            SpringApplication missingPublicCaseOrigin = new SpringApplication(YudaoPlatformServerApplication.class, TestRedisPool.class);
            missingPublicCaseOrigin.setAdditionalProfiles("pg", "local");
            assertThatThrownBy(() -> missingPublicCaseOrigin.run(options.toArray(String[]::new)))
                    .hasStackTraceContaining("zhongshu.design.public-cases.origin is required");
            options.add("--zhongshu.design.public-cases.origin=https://public-cases.example.test");
            try (var context = (ServletWebServerApplicationContext) api.run(options.toArray(String[]::new))) {
                assertThat(context.getEnvironment().getProperty("spring.flyway.password")).isNull();
                for (String legacy : List.of("zhongshuJobDriver", "zhongshuExportWorker", "privacyLifecycleService",
                        "assetValidationRecoveryJob", "refundRecoveryJob", "aiJobCancelRecoveryJob",
                        "walletSessionController", "walletSessionService", "walletSessionRevocationAdapter")) {
                    assertThat(context.containsBean(legacy)).as("B must not register %s", legacy).isFalse();
                }
                assertThat(context.containsBean("contentTaskJob")).isTrue();
                assertThat(context.containsBean("saleContractService")).isTrue();
                assertThat(context.containsBean("finReceiptService")).isTrue();
                assertThat(context.containsBean("engPlanService")).isTrue();
                assertThat(context.getBean(PublicCaseAccessService.class)).isNotNull();
                assertThat(context.getBean(org.quartz.Scheduler.class).isStarted()).isFalse();
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                                + context.getWebServer().getPort() + "/actuator/health/readiness"))
                        .timeout(Duration.ofSeconds(10)).GET().build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("components", "details");
                verifyAdminLoginAndHeadquartersOnboard(http, context.getWebServer().getPort());
                redis.stop();
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(503);
                assertThat(response.body()).contains("\"status\":\"DOWN\"").doesNotContain("components", "details");
            }
        }
    }

    @Test
    void migrationRejectsUnknownNonemptySchemaAndRepeatedMigrationDoesNotRewriteHistory() throws Exception {
        try (var pg = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.15-alpine"))
                .withDatabaseName("zhongshu_platform_b").withUsername("platform_owner").withPassword("owner-test-password")) {
            pg.start();
            String[] arguments = {"migrate", "--spring.profiles.active=pg,migration", externalConfiguration(true),
                    "--spring.flyway.url=" + pg.getJdbcUrl(), "--spring.flyway.user=platform_owner",
                    "--spring.flyway.password=owner-test-password"};
            try (var connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE unknown_existing_data(id BIGINT)");
                assertThatThrownBy(() -> YudaoPlatformServerApplication.main(arguments))
                        .hasStackTraceContaining("non-empty schema");
                try (var result = statement.executeQuery("SELECT to_regclass('public.flyway_schema_history')")) {
                    result.next();
                    assertThat(result.getString(1)).isNull();
                }
                statement.execute("DROP TABLE unknown_existing_data");
                YudaoPlatformServerApplication.main(arguments);
                String history;
                try (var result = statement.executeQuery("SELECT string_agg(version || ':' || checksum, ',' ORDER BY installed_rank) "
                        + "FROM flyway_schema_history WHERE success")) {
                    result.next();
                    history = result.getString(1);
                    assertThat(history).isNotBlank();
                }
                YudaoPlatformServerApplication.main(arguments);
                try (var result = statement.executeQuery("SELECT string_agg(version || ':' || checksum, ',' ORDER BY installed_rank) "
                        + "FROM flyway_schema_history WHERE success")) {
                    result.next();
                    assertThat(result.getString(1)).isEqualTo(history);
                }
            }
        }
    }

    @Test
    void failedBootstrapRollsBackAllSystemTables() throws Exception {
        Path migrationDirectory = Files.createDirectory(temporaryDirectory.resolve("failing-migration"));
        String resource = "db/migration/platform-bootstrap/V20260904.001__platform_system_infra.sql";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            Files.writeString(migrationDirectory.resolve("V1__bootstrap_rollback.sql"),
                    new String(input.readAllBytes(), StandardCharsets.UTF_8) + "\nSELECT 1 / 0;\n");
        }
        try (var pg = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.15-alpine"))
                .withDatabaseName("zhongshu_platform_b").withUsername("platform_owner").withPassword("owner-test-password")) {
            pg.start();
            var flyway = org.flywaydb.core.Flyway.configure()
                    .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                    .locations("filesystem:" + migrationDirectory).load();
            assertThatThrownBy(flyway::migrate).hasStackTraceContaining("division by zero");
            try (var connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT to_regclass('system_users'), to_regclass('system_tenant')")) {
                rows.next();
                assertThat(rows.getString(1)).isNull();
                assertThat(rows.getString(2)).isNull();
            }
        }
    }

    private String externalConfiguration(boolean maintenance) throws Exception {
        Properties properties = new Properties();
        List<String> resources = new ArrayList<>(List.of("application.properties", "application-pg.properties"));
        if (maintenance) {
            resources.add("application-migration.properties");
        }
        for (String resource : resources) {
            try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertThat(input).as(resource).isNotNull();
                properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            }
        }
        // Testcontainers has its own temporary external configuration, not the production Docker mount paths.
        properties.remove("spring.config.import");
        Path path = temporaryDirectory.resolve(maintenance ? "maintenance.properties" : "runtime.properties");
        try (var output = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            properties.store(output, "Isolated integration test configuration");
        }
        return "--spring.config.location=" + path.toUri();
    }

    private static void verifyAdminLoginAndHeadquartersOnboard(HttpClient http, int port) throws Exception {
        String base = "http://127.0.0.1:" + port + "/admin-api";
        ObjectMapper json = new ObjectMapper();
        var login = http.send(HttpRequest.newBuilder(URI.create(base + "/system/auth/login"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json").header("tenant-id", "1")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"username":"platformadmin","password":"TestOnly#852963"}
                        """)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        var loginBody = json.readTree(login.body());
        assertThat(loginBody.path("code").asInt(-1)).as("real admin login").isZero();
        String token = loginBody.path("data").path("accessToken").asText();
        assertThat(token).isNotBlank();
        var permission = http.send(HttpRequest.newBuilder(URI.create(base + "/system/auth/get-permission-info"))
                .timeout(Duration.ofSeconds(10)).header("tenant-id", "1")
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(permission.statusCode()).isEqualTo(200);
        assertThat(json.readTree(permission.body()).path("code").asInt(-1)).isZero();
        var onboard = http.send(HttpRequest.newBuilder(URI.create(base + "/platform/org/onboard"))
                .timeout(Duration.ofSeconds(10)).header("tenant-id", "1").header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"orgType":"headquarters","orgName":"B验收总部","adminName":"验收负责人","adminMobile":"13800001999"}
                        """)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(onboard.statusCode()).isEqualTo(200);
        var onboardBody = json.readTree(onboard.body());
        assertThat(onboardBody.path("code").asInt(-1)).as("headquarters onboarding: %s", onboardBody.path("msg")).isZero();
        assertThat(onboardBody.path("data").path("orgId").asLong()).isPositive();
        assertThat(onboardBody.path("data").path("postId").asLong()).isPositive();
    }
}
