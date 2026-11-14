package cn.iocoder.yudao.designprovider;

import cn.iocoder.yudao.module.franchise.api.capability.security.CapabilitySignature;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DesignProviderWebSecurityIntegrationTest {
    private static final byte[] SECRET = "test-only-capability-secret-32b!".getBytes(StandardCharsets.UTF_8);

    @Test
    void realServletStackExposesOnlyCapabilityAndEnforcesHmacNonceAndCaseBoundary() throws Exception {
        try (var postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.15-alpine"))
                .withDatabaseName("design_provider").withUsername("owner").withPassword("test-password");
             var redis = new GenericContainer<>(DockerImageName.parse("redis:7.4.11-alpine")).withExposedPorts(6379)
                     .withCommand("redis-server", "--user", "default", "off", "--user", "design_provider_app",
                             "on", ">redis-test-password", "~*", "&*", "+@all")) {
            postgres.start();
            redis.start();
            migrateAndSeed(postgres);
            HttpServer runtime = startReceiptRuntime();
            try {
                var application = new SpringApplication(DesignProviderApplication.class, TestOnlyRuntimeFixture.class);
                application.setWebApplicationType(WebApplicationType.SERVLET);
                try (var context = application.run(
                        "--server.port=0",
                        "--spring.datasource.dynamic.datasource.master.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.dynamic.datasource.master.username=" + postgres.getUsername(),
                        "--spring.datasource.dynamic.datasource.master.password=" + postgres.getPassword(),
                        "--spring.data.redis.host=" + redis.getHost(),
                        "--spring.data.redis.port=" + redis.getMappedPort(6379),
                        "--spring.data.redis.password=redis-test-password",
                        "--zhongshu.design.asset.storage.provider=local",
                        "--zhongshu.design.asset.moderation.provider=stub",
                        "--zhongshu.ai.note-polish.runtime-url=http://127.0.0.1:" + runtime.getAddress().getPort(),
                        "--zhongshu.ai.internal-secret=test-only-runtime-secret",
                        "--zhongshu.design.capability.keys.test.caller-app-id=platform-b-test",
                        "--zhongshu.design.capability.keys.test.secret-base64=" + Base64.getEncoder().encodeToString(SECRET),
                        "--zhongshu.design.capability.keys.test.operations=LIST_PROJECTS,CREATE_PROJECT")) {
                    assertExactMappings(context.getBean("requestMappingHandlerMapping",
                            RequestMappingHandlerMapping.class));
                    int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
                    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                            .getLogger(ProviderCapabilityBoundaryConfiguration.class);
                    var appender = new ListAppender<ILoggingEvent>();
                    appender.start();
                    logger.addAppender(appender);
                    try {
                        verifySignedNetworkBoundary(port);
                        assertSanitizedCaseReferenceWarning(appender);
                    } finally {
                        logger.detachAppender(appender);
                        appender.stop();
                    }
                }
            } finally {
                runtime.stop(0);
            }
        }
    }

    private static void assertSanitizedCaseReferenceWarning(ListAppender<ILoggingEvent> appender) {
        assertThat(appender.list).hasSize(1);
        ILoggingEvent warning = appender.list.get(0);
        assertThat(warning.getLevel()).isEqualTo(Level.WARN);
        assertThat(warning.getFormattedMessage())
                .contains("reason=AUTHORIZATION_NOT_READY", "caller=platform-b-test", "org=1",
                        "operation=CREATE_PROJECT",
                        "requestKeyDigest=" + ProviderCapabilityBoundaryConfiguration.requestKeyDigest("case-1"))
                .doesNotContain("case-1", "CASE_REFERENCE", Base64.getEncoder().encodeToString(SECRET),
                        "test-only-capability-secret");
    }

    private static void assertExactMappings(RequestMappingHandlerMapping mappings) {
        Set<String> paths = mappings.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream())
                .collect(Collectors.toSet());
        assertThat(paths).containsExactlyInAnyOrder("/error", "/internal-api/design/v1/platform/{action}");
        assertThat(paths).noneMatch(path -> path.startsWith("/app-api") || path.startsWith("/admin-api"));
    }

    private static void verifySignedNetworkBoundary(int port) throws Exception {
        String path = "/internal-api/design/v1/platform/list-projects";
        byte[] body = ("{\"callerAppId\":\"platform-b-test\",\"orgId\":1,"
                + "\"actor\":{\"actorUserId\":2,\"actorPostId\":3,\"actorRoleCode\":\"DESIGNER\"},"
                + "\"operation\":\"LIST_PROJECTS\",\"requestKey\":\"list-1\","
                + "\"nonce\":\"nonce-0000000001\",\"payload\":{\"cursor\":null,\"limit\":20}}")
                .getBytes(StandardCharsets.UTF_8);
        var client = HttpClient.newHttpClient();
        assertThat(client.send(unsigned(port, path, body), HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);

        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        assertThat(client.send(signed(port, path, body, timestamp, "0".repeat(64)),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);

        String signature = CapabilitySignature.sign(SECRET, timestamp, "POST", path, body);
        assertThat(client.send(signed(port, path, body, timestamp, signature),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(client.send(signed(port, path, body, timestamp, signature),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);

        String casePath = "/internal-api/design/v1/platform/create-project";
        byte[] caseBody = ("{\"callerAppId\":\"platform-b-test\",\"orgId\":1,"
                + "\"actor\":{\"actorUserId\":2,\"actorPostId\":3,\"actorRoleCode\":\"DESIGNER\"},"
                + "\"operation\":\"CREATE_PROJECT\",\"requestKey\":\"case-1\","
                + "\"nonce\":\"nonce-0000000002\",\"payload\":{\"sourceType\":\"CASE_REFERENCE\","
                + "\"refCaseId\":99,\"sketchAssetId\":null,\"requirementInputs\":{},"
                + "\"line\":\"FLAT\",\"idempotencyKey\":\"case-1\"}}")
                .getBytes(StandardCharsets.UTF_8);
        String caseTimestamp = String.valueOf(Instant.now().getEpochSecond());
        String caseSignature = CapabilitySignature.sign(SECRET, caseTimestamp, "POST", casePath, caseBody);
        var response = client.send(signed(port, casePath, caseBody, caseTimestamp, caseSignature),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("CASE_REFERENCE_PROVIDER_AUTHORIZATION_NOT_READY");
    }

    private static HttpRequest unsigned(int port, String path, byte[] body) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }

    private static HttpRequest signed(int port, String path, byte[] body, String timestamp, String signature) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header(CapabilitySignature.KEY_ID_HEADER, "test")
                .header(CapabilitySignature.TIMESTAMP_HEADER, timestamp)
                .header(CapabilitySignature.SIGNATURE_HEADER, signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }

    private static HttpServer startReceiptRuntime() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/note-polish/receipt", exchange -> {
            byte[] response = "{\"status\":\"NOT_FOUND\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static void migrateAndSeed(PostgreSQLContainer<?> postgres) throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/identity", "classpath:db/migration/design",
                        "classpath:db/migration/commerce", "classpath:db/migration/ai-orchestration",
                        "classpath:db/migration/design-capability")
                .baselineOnMigrate(false).outOfOrder(false).cleanDisabled(true).load().migrate();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("INSERT INTO design_capability_organization(caller_app_id,org_id,tenant_id,active) "
                    + "VALUES ('platform-b-test',1,1,true)");
            for (String product : new String[]{"FLAT:2K", "FLAT:4K", "ELEVATION:2K", "ELEVATION:4K",
                    "BUDGET_ESTIMATE", "NOTE_POLISH"}) {
                statement.execute("INSERT INTO platform_cost_price(product,version,unit,prompt_micros,image_micros,usage_micros) "
                        + "VALUES ('" + product + "',1,'MICRO_CNY',1,1,1)");
            }
        }
    }

    /** Test-only closure fixture; production has no ProviderRuntimeDriver and remains fail-closed. */
    @Configuration(proxyBeanMethods = false)
    static class TestOnlyRuntimeFixture {
        @Bean
        ProviderRuntimeDriver testOnlyProviderRuntimeDriver() {
            return () -> { };
        }
    }
}
