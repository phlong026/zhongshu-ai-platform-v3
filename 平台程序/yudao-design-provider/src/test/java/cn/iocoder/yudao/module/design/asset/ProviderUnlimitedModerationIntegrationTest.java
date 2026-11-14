package cn.iocoder.yudao.module.design.asset;

import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** B-specific proof that daily-limit=0 records every real moderation request without blocking it. */
class ProviderUnlimitedModerationIntegrationTest {

    @Test
    void zeroDailyLimitStillSendsEveryRequestAndIncrementsUsage() throws Exception {
        try (var postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.15-alpine"))
                .withDatabaseName("design_provider").withUsername("owner").withPassword("test-password")) {
            postgres.start();
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration/identity", "classpath:db/migration/design",
                            "classpath:db/migration/commerce", "classpath:db/migration/ai-orchestration",
                            "classpath:db/migration/design-capability")
                    .baselineOnMigrate(false).outOfOrder(false).cleanDisabled(true).load().migrate();

            var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword());
            var database = new JdbcTemplate(dataSource);
            var requests = new AtomicInteger();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                int requestNumber = requests.incrementAndGet();
                byte[] response = ("{\"Response\":{\"Suggestion\":\"Pass\",\"RequestId\":\"local-"
                        + requestNumber + "\"}}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            try {
                var adapter = new TencentImsModerationAdapter("test-id", "test-key", "ap-guangzhou",
                        "test-policy", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                        TencentImsModerationAdapter.quotaGuard(database, 0),
                        Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneOffset.UTC));

                for (int index = 0; index < 3; index++) {
                    assertThat(adapter.review("AI_OUTPUT", new byte[]{1, 2, (byte) index}).accepted()).isTrue();
                }
                assertThat(requests).hasValue(3);
                assertThat(database.queryForObject("SELECT calls FROM moderation_daily_usage", Integer.class))
                        .isEqualTo(3);
            } finally {
                server.stop(0);
            }
        }
    }
}
