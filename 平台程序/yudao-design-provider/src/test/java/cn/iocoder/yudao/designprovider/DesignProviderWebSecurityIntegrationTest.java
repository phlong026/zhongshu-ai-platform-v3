package cn.iocoder.yudao.designprovider;

import cn.iocoder.yudao.module.franchise.api.capability.security.CapabilitySignature;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;

class DesignProviderWebSecurityIntegrationTest {
    private static final byte[] SECRET = "test-only-capability-secret-32b!".getBytes(StandardCharsets.UTF_8);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] PNG = HexFormat.of().parseHex(
            "89504e470d0a1a0a0000000d4948445200000001000000010802000000907753de"
                    + "0000000c49444154789c63f8cfc0000003010100c9fe92ef0000000049454e44ae426082");

    @Test
    void realServletStackExposesOnlyCapabilityAndEnforcesHmacNonceAndCaseBoundary() throws Exception {
        try (var postgres = cn.iocoder.yudao.module.platform.test.TestPostgres.database(DockerImageName.parse("postgres:16.15-alpine"))
                .withDatabaseName("design_provider").withUsername("owner").withPassword("test-password");
             var redis = cn.iocoder.yudao.module.platform.test.TestNativeService.container(DockerImageName.parse("redis:7.4.11-alpine")).withExposedPorts(6379)
                     .withCommand("redis-server", "--user", "default", "off", "--user", "design_provider_app",
                             "on", ">redis-test-password", "~*", "&*", "+@all")) {
            postgres.start();
            redis.start();
            migrateAndSeed(postgres);
            Path storageRoot = Files.createTempDirectory("design-provider-runtime-");
            HttpServer runtime = startReceiptRuntime();
            try {
                var application = new SpringApplication(DesignProviderApplication.class);
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
                        "--zhongshu.design.asset.storage-root=" + storageRoot,
                        "--zhongshu.design.asset.moderation.provider=stub",
                        "--zhongshu.ai.note-polish.runtime-url=http://127.0.0.1:" + runtime.getAddress().getPort(),
                        "--zhongshu.ai.internal-secret=test-only-runtime-secret",
                        "--zhongshu.ai.internal-replay-window=600",
                        "--zhongshu.design.driver-enabled=true",
                        "--zhongshu.design.driver-initial-delay-ms=3600000",
                        "--zhongshu.design.capability.keys.test.caller-app-id=platform-b-test",
                        "--zhongshu.design.capability.keys.test.secret-base64=" + Base64.getEncoder().encodeToString(SECRET),
                        "--zhongshu.design.capability.keys.test.operations=LIST_PROJECTS,CREATE_PROJECT")) {
                    assertExactMappings(context.getBean("requestMappingHandlerMapping",
                            RequestMappingHandlerMapping.class));
                    int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
                    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                            .getLogger(ProviderCapabilityBoundaryConfiguration.class);
                    var appender = new ListAppender<ILoggingEvent>();
                    var runtimeLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                            .getLogger(ProviderRuntimeSecurityConfiguration.RuntimeAuthenticationFilter.class);
                    var runtimeAppender = new ListAppender<ILoggingEvent>();
                    appender.start();
                    runtimeAppender.start();
                    logger.addAppender(appender);
                    runtimeLogger.addAppender(runtimeAppender);
                    try {
                        verifySignedNetworkBoundary(port);
                        verifyRuntimeNetworkBoundary(port,
                                context.getBean(org.springframework.data.redis.core.StringRedisTemplate.class));
                        verifyRuntimeJobClosure(context, port, storageRoot);
                        assertThat(HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                                        URI.create("http://127.0.0.1:" + port + "/actuator/health")).GET().build(),
                                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
                        verifyRedisFailClosed(port, redis);
                        assertSanitizedCaseReferenceWarning(appender);
                        assertSanitizedRuntimeWarnings(runtimeAppender);
                    } finally {
                        logger.detachAppender(appender);
                        runtimeLogger.detachAppender(runtimeAppender);
                        appender.stop();
                        runtimeAppender.stop();
                    }
                }
            } finally {
                runtime.stop(0);
            }
        }
    }

    private static void assertSanitizedRuntimeWarnings(ListAppender<ILoggingEvent> appender) {
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("reason=INVALID_SIGNATURE"))
                .anyMatch(message -> message.contains("reason=REPLAY_REJECTED"))
                .anyMatch(message -> message.contains("reason=REPLAY_STORE_UNAVAILABLE"));
        assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain("test-only-runtime-secret", "X-ZS-Signature", "/internal-api/"));
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
        assertThat(paths).containsExactlyInAnyOrder(
                "/error", "/internal-api/design/v1/platform/{action}",
                "/internal-api/deploy-smoke/platform",
                "/internal-api/design/v1/ai-jobs/auth-smoke",
                "/internal-api/design/v1/ai-jobs/claims",
                "/internal-api/design/v1/ai-jobs/{jobId}/lease-renewals",
                "/internal-api/design/v1/ai-jobs/{jobId}/prompt-calls",
                "/internal-api/design/v1/ai-jobs/{jobId}/prompt-call-events",
                "/internal-api/design/v1/ai-jobs/{jobId}/progress-events",
                "/internal-api/design/v1/ai-jobs/{jobId}/result-events",
                "/internal-api/design/v1/ai-jobs/{jobId}/failure-events",
                "/internal-api/design/v1/ai-jobs/{jobId}/completion-events",
                "/internal-api/design/v1/ai-jobs/{jobId}/call-traces",
                "/internal-api/design/v1/ai-jobs/{jobId}/call-traces/reconciliations",
                "/internal-api/design/v1/ai-jobs/{id}/inputs",
                "/internal-api/design/v1/ai-jobs/{id}/output-tickets");
        assertThat(paths).noneMatch(path -> path.startsWith("/app-api") || path.startsWith("/admin-api")
                || path.toLowerCase().contains("payment"));
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

    private static void verifyRuntimeNetworkBoundary(int port,
            org.springframework.data.redis.core.StringRedisTemplate redis) throws Exception {
        String path = "/internal-api/design/v1/ai-jobs/claims";
        byte[] body = "{\"workerId\":\"runtime-b-test\",\"maxJobs\":1}"
                .getBytes(StandardCharsets.UTF_8);
        var client = HttpClient.newHttpClient();
        assertThat(client.send(unsigned(port, path, body), HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        assertThat(client.send(runtimeSigned(port, path, body, timestamp, "0".repeat(64)),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);

        String signature = runtimeSignature("test-only-runtime-secret", timestamp, path, body);
        var accepted = client.send(runtimeSigned(port, path, body, timestamp, signature),
                HttpResponse.BodyHandlers.ofString());
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(accepted.body()).contains("\"code\":0", "\"data\":[]");
        assertThat(client.send(runtimeSigned(port, path, body, timestamp, signature),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);

        byte[] futureBody = "{\"workerId\":\"runtime-b-future\",\"maxJobs\":1}"
                .getBytes(StandardCharsets.UTF_8);
        String futureTimestamp = String.valueOf(Instant.now().plusSeconds(599).getEpochSecond());
        String futureSignature = runtimeSignature("test-only-runtime-secret", futureTimestamp, path, futureBody);
        assertThat(client.send(runtimeSigned(port, path, futureBody, futureTimestamp, futureSignature),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        Long futureTtl = redis.getExpire("design-provider:runtime-replay:" + futureSignature,
                java.util.concurrent.TimeUnit.SECONDS);
        assertThat(futureTtl).isBetween(1197L, 1200L);
        assertThat(client.send(runtimeSigned(port, path, futureBody, futureTimestamp, futureSignature),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
    }

    private static void verifyRedisFailClosed(int port, GenericContainer<?> redis) throws Exception {
        redis.stop();
        String path = "/internal-api/design/v1/ai-jobs/claims";
        byte[] body = "{\"workerId\":\"runtime-b-redis-down\",\"maxJobs\":1}".getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = runtimeSignature("test-only-runtime-secret", timestamp, path, body);
        assertThat(HttpClient.newHttpClient().send(runtimeSigned(port, path, body, timestamp, signature),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(503);
    }

    private static void verifyRuntimeJobClosure(org.springframework.context.ApplicationContext context,
                                                int port, Path storageRoot) throws Exception {
        var jdbc = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        jdbc.update("INSERT INTO account(id,status) VALUES (8001,'ACTIVE')");
        jdbc.update("INSERT INTO design_project(id,user_id,source_type,stage) VALUES (7001,8001,'SELF_UPLOAD','FLAT')");
        jdbc.update("INSERT INTO ai_job(id,user_id,project_ref,phase,status,requested_count,output_prefix,"
                        + "unit_point_cost,total_point_cost,input_snapshot) VALUES "
                        + "(9001,8001,'7001','FLAT','QUEUED',1,'ai-quarantine/9001',0,0,?::jsonb)",
                "{\"phase\":\"FLAT\",\"requirements\":{},\"assetIds\":[]}");

        JsonNode claim = runtimePost(port, "/internal-api/design/v1/ai-jobs/claims",
                "{\"workerId\":\"runtime-b-closure\",\"maxJobs\":1}");
        JsonNode job = claim.path("data").get(0);
        int attempt = job.path("attemptNo").asInt();
        long fence = job.path("fencingToken").asLong();
        String lease = "{\"attemptNo\":" + attempt + ",\"fencingToken\":" + fence + "}";
        assertThat(runtimePost(port, "/internal-api/design/v1/ai-jobs/9001/inputs", lease)
                .path("data").path("schemaVersion").asInt()).isEqualTo(1);

        String ticketBody = "{\"attemptNo\":" + attempt + ",\"fencingToken\":" + fence
                + ",\"slot\":1,\"mimeType\":\"image/png\"}";
        JsonNode ticket = runtimePost(port, "/internal-api/design/v1/ai-jobs/9001/output-tickets", ticketBody).path("data");
        String objectKey = ticket.path("objectKey").asText();
        JsonNode retriedTicket = JSON.readTree(runtimePostResponse(port,
                "/internal-api/design/v1/ai-jobs/9001/output-tickets", ticketBody, 1).body()).path("data");
        assertThat(retriedTicket.path("objectKey").asText()).isEqualTo(objectKey);
        Path output = storageRoot.resolve(objectKey).normalize();
        assertThat(output.toString()).startsWith(storageRoot.toString());
        Files.createDirectories(output.getParent());
        Files.write(output, PNG);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PNG));
        assertRuntimeRejected(port, "/internal-api/design/v1/ai-jobs/9001/result-events",
                "{\"attemptNo\":" + attempt + ",\"fencingToken\":" + fence
                        + ",\"providerCode\":\"apilio\",\"sourceEventId\":\"wrong-slot\","
                        + "\"candidateSlotNo\":2,\"objectKey\":\"" + objectKey + "\",\"sha256\":\"" + sha
                        + "\",\"mimeType\":\"image/png\",\"sizeBytes\":" + PNG.length + "}", 409);
        String unissued = "ai-quarantine/9001/" + fence + "/1/unissued.png";
        assertRuntimeRejected(port, "/internal-api/design/v1/ai-jobs/9001/result-events",
                "{\"attemptNo\":" + attempt + ",\"fencingToken\":" + fence
                        + ",\"providerCode\":\"apilio\",\"sourceEventId\":\"unissued\","
                        + "\"candidateSlotNo\":1,\"objectKey\":\"" + unissued + "\",\"sha256\":\"" + sha
                        + "\",\"mimeType\":\"image/png\",\"sizeBytes\":" + PNG.length + "}", 409);
        String resultBody = "{\"attemptNo\":" + attempt + ",\"fencingToken\":" + fence
                + ",\"providerCode\":\"apilio\",\"sourceEventId\":\"closure-result-1\","
                + "\"candidateSlotNo\":1,\"objectKey\":\"" + objectKey + "\",\"sha256\":\"" + sha
                + "\",\"mimeType\":\"image/png\",\"sizeBytes\":" + PNG.length + "}";
        JsonNode result = runtimePost(port, "/internal-api/design/v1/ai-jobs/9001/result-events",
                resultBody);
        assertThat(result.path("data").asText()).isEqualTo("QUARANTINED");
        Thread.sleep(1_050L);
        assertThat(runtimePost(port, "/internal-api/design/v1/ai-jobs/9001/result-events", resultBody)
                .path("data").asText()).isEqualTo("DUPLICATE_EVENT");
        var duplicateOne = CompletableFuture.supplyAsync(() -> runtimePostUnchecked(port,
                "/internal-api/design/v1/ai-jobs/9001/result-events", resultBody, 1));
        var duplicateTwo = CompletableFuture.supplyAsync(() -> runtimePostUnchecked(port,
                "/internal-api/design/v1/ai-jobs/9001/result-events", resultBody, -2));
        assertThat(duplicateOne.join().statusCode()).isEqualTo(200);
        assertThat(duplicateTwo.join().statusCode()).isEqualTo(200);
        assertRuntimeRejected(port, "/internal-api/design/v1/ai-jobs/9001/result-events",
                resultBody.replace("closure-result-1", "closure-result-tampered"), 409);
        String tamperedSha = (sha.startsWith("0") ? "1" : "0") + sha.substring(1);
        assertRuntimeRejected(port, "/internal-api/design/v1/ai-jobs/9001/result-events",
                resultBody.replace(sha, tamperedSha), 409);
        assertRuntimeRejected(port, "/internal-api/design/v1/ai-jobs/9001/result-events",
                resultBody.replace("\"sizeBytes\":" + PNG.length,
                        "\"sizeBytes\":" + (PNG.length + 1)), 409);
        Map<String, Object> consumedTicket = jdbc.queryForMap("SELECT state,source_event_id,content_sha256,size_bytes "
                + "FROM design_provider_output_ticket WHERE object_key=?", objectKey);
        assertThat(consumedTicket).containsEntry("state", "CONSUMED")
                .containsEntry("source_event_id", "closure-result-1")
                .containsEntry("content_sha256", sha);
        assertThat(((Number) consumedTicket.get("size_bytes")).longValue()).isEqualTo(PNG.length);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job_result WHERE job_id=9001", Integer.class))
                .isEqualTo(1);
        assertThat(runtimePost(port, "/internal-api/design/v1/ai-jobs/9001/completion-events", lease)
                .path("data").asBoolean()).isTrue();

        ProviderDesignJobDriver driver = context.getBean(ProviderDesignJobDriver.class);
        driver.validationTick();
        assertThat(jdbc.queryForObject("SELECT validation_state FROM ai_job_result WHERE job_id=9001", String.class))
                .isEqualTo("ACCEPTED");
        driver.settlementTick();
        assertThat(jdbc.queryForObject("SELECT status FROM ai_job WHERE id=9001", String.class)).isEqualTo("SUCCEEDED");
        driver.promotionTick();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_candidate WHERE job_id=9001", Integer.class)).isEqualTo(1);
        driver.outboxTick();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE status='PENDING'", Integer.class)).isZero();

        jdbc.update("INSERT INTO ai_job(id,user_id,phase,status,requested_count,output_prefix,unit_point_cost,total_point_cost,input_snapshot) "
                        + "VALUES (9002,8001,'FLAT','QUEUED',1,'ai-quarantine/9002',0,0,?::jsonb)",
                "{\"phase\":\"FLAT\",\"requirements\":{},\"assetIds\":[]}");
        JsonNode retryClaim = runtimePost(port, "/internal-api/design/v1/ai-jobs/claims",
                "{\"workerId\":\"runtime-b-retry\",\"maxJobs\":1}").path("data").get(0);
        int firstAttempt = retryClaim.path("attemptNo").asInt();
        long firstFence = retryClaim.path("fencingToken").asLong();
        String oldTicket = runtimePost(port, "/internal-api/design/v1/ai-jobs/9002/output-tickets",
                "{\"attemptNo\":" + firstAttempt + ",\"fencingToken\":" + firstFence
                        + ",\"slot\":1,\"mimeType\":\"image/png\"}").path("data").path("objectKey").asText();
        assertThat(runtimePost(port, "/internal-api/design/v1/ai-jobs/9002/failure-events",
                "{\"attemptNo\":" + firstAttempt + ",\"fencingToken\":" + firstFence
                        + ",\"providerCode\":\"apilio\",\"sourceEventId\":\"retry-failure-1\","
                        + "\"errorCode\":\"PROVIDER_TIMEOUT\",\"message\":\"safe\"}")
                .path("data").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM ai_job WHERE id=9002", String.class)).isEqualTo("QUEUED");
        var staleTicketResponse = runtimePostResponse(port, "/internal-api/design/v1/ai-jobs/9002/output-tickets",
                "{\"attemptNo\":" + firstAttempt + ",\"fencingToken\":" + firstFence
                        + ",\"slot\":1,\"mimeType\":\"image/png\"}", 1);
        assertThat(staleTicketResponse.statusCode()).isEqualTo(403);
        // The wire contract has second-resolution timestamps and no nonce; a legitimate identical poll must be re-signed
        // in a later second, while a byte-for-byte replay inside the same second is rejected above.
        Thread.sleep(1_050L);
        JsonNode secondClaim = runtimePost(port, "/internal-api/design/v1/ai-jobs/claims",
                "{\"workerId\":\"runtime-b-retry\",\"maxJobs\":1}").path("data").get(0);
        assertThat(secondClaim.path("attemptNo").asInt()).isEqualTo(firstAttempt + 1);
        assertThat(secondClaim.path("fencingToken").asLong()).isGreaterThan(firstFence);
        assertRuntimeRejected(port, "/internal-api/design/v1/ai-jobs/9002/result-events",
                "{\"attemptNo\":" + secondClaim.path("attemptNo").asInt() + ",\"fencingToken\":"
                        + secondClaim.path("fencingToken").asLong() + ",\"providerCode\":\"apilio\","
                        + "\"sourceEventId\":\"old-fence-ticket\",\"candidateSlotNo\":1,\"objectKey\":\""
                        + oldTicket + "\",\"sha256\":\"" + sha + "\",\"mimeType\":\"image/png\","
                        + "\"sizeBytes\":" + PNG.length + "}", 409);

        jdbc.update("INSERT INTO ai_job(id,user_id,phase,status,requested_count,output_prefix,unit_point_cost,total_point_cost,input_snapshot) "
                        + "VALUES (9003,8001,'FLAT','QUEUED',1,'ai-quarantine/9003',0,0,?::jsonb)",
                "{\"phase\":\"FLAT\",\"requirements\":{},\"assetIds\":[]}");
        JsonNode rollbackJob = runtimePost(port, "/internal-api/design/v1/ai-jobs/claims",
                "{\"workerId\":\"runtime-b-rollback\",\"maxJobs\":1}").path("data").get(0);
        int rollbackAttempt = rollbackJob.path("attemptNo").asInt();
        long rollbackFence = rollbackJob.path("fencingToken").asLong();
        String rollbackKey = runtimePost(port, "/internal-api/design/v1/ai-jobs/9003/output-tickets",
                "{\"attemptNo\":" + rollbackAttempt + ",\"fencingToken\":" + rollbackFence
                        + ",\"slot\":1,\"mimeType\":\"image/png\"}").path("data").path("objectKey").asText();
        String validResult = "{\"attemptNo\":" + rollbackAttempt + ",\"fencingToken\":" + rollbackFence
                + ",\"providerCode\":\"apilio\",\"sourceEventId\":\"rollback-result\",\"candidateSlotNo\":1,\"objectKey\":\""
                + rollbackKey + "\",\"sha256\":\"" + sha + "\",\"mimeType\":\"image/png\",\"sizeBytes\":"
                + PNG.length + "}";
        jdbc.execute("CREATE FUNCTION provider_test_fail_ticket_consume() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'forced ticket consume rollback'; END $$");
        jdbc.execute("CREATE TRIGGER provider_test_fail_ticket_consume BEFORE UPDATE ON design_provider_output_ticket "
                + "FOR EACH ROW EXECUTE FUNCTION provider_test_fail_ticket_consume()");
        assertRuntimeRejected(port, "/internal-api/design/v1/ai-jobs/9003/result-events", validResult, 500);
        assertThat(jdbc.queryForObject("SELECT state FROM design_provider_output_ticket WHERE job_id=9003", String.class))
                .isEqualTo("ISSUED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job_result WHERE job_id=9003", Integer.class)).isZero();
        jdbc.execute("DROP TRIGGER provider_test_fail_ticket_consume ON design_provider_output_ticket");
        jdbc.execute("DROP FUNCTION provider_test_fail_ticket_consume()");
        Thread.sleep(1_050L);
        assertThat(runtimePost(port, "/internal-api/design/v1/ai-jobs/9003/result-events", validResult)
                .path("data").asText()).isEqualTo("QUARANTINED");

        jdbc.update("INSERT INTO ai_job(id,user_id,phase,status,requested_count,output_prefix,unit_point_cost,total_point_cost,input_snapshot) "
                        + "VALUES (9004,8001,'FLAT','QUEUED',1,'ai-quarantine/9004',0,0,?::jsonb)",
                "{\"phase\":\"FLAT\",\"requirements\":{},\"assetIds\":[]}");
        JsonNode collisionJob = runtimePost(port, "/internal-api/design/v1/ai-jobs/claims",
                "{\"workerId\":\"runtime-b-collision\",\"maxJobs\":1}").path("data").get(0);
        int collisionAttempt = collisionJob.path("attemptNo").asInt();
        long collisionFence = collisionJob.path("fencingToken").asLong();
        String collisionKey = runtimePost(port, "/internal-api/design/v1/ai-jobs/9004/output-tickets",
                "{\"attemptNo\":" + collisionAttempt + ",\"fencingToken\":" + collisionFence
                        + ",\"slot\":1,\"mimeType\":\"image/png\"}").path("data").path("objectKey").asText();
        jdbc.update("INSERT INTO ai_result_event_inbox(id,job_id,attempt_no,provider_code,source_event_id,event_kind,"
                        + "payload,received_seq,process_status) VALUES (99004,9002,2,'apilio','cross-job-source',"
                        + "'RESULT','{}'::jsonb,1,'RECEIVED')");
        String collisionResult = "{\"attemptNo\":" + collisionAttempt + ",\"fencingToken\":" + collisionFence
                + ",\"providerCode\":\"apilio\",\"sourceEventId\":\"cross-job-source\",\"candidateSlotNo\":1,"
                + "\"objectKey\":\"" + collisionKey + "\",\"sha256\":\"" + sha
                + "\",\"mimeType\":\"image/png\",\"sizeBytes\":" + PNG.length + "}";
        assertRuntimeRejected(port, "/internal-api/design/v1/ai-jobs/9004/result-events", collisionResult, 409);
        assertThat(jdbc.queryForObject("SELECT state FROM design_provider_output_ticket WHERE job_id=9004", String.class))
                .isEqualTo("ISSUED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job_result WHERE job_id=9004", Integer.class)).isZero();

        jdbc.update("INSERT INTO ai_job(id,user_id,phase,status,requested_count,output_prefix,unit_point_cost,total_point_cost,input_snapshot) "
                        + "VALUES (9005,8001,'FLAT','QUEUED',1,'ai-quarantine/9005',0,0,?::jsonb)",
                "{\"phase\":\"FLAT\",\"requirements\":{},\"assetIds\":[]}");
        JsonNode racedJob = runtimePost(port, "/internal-api/design/v1/ai-jobs/claims",
                "{\"workerId\":\"runtime-b-ticket-race\",\"maxJobs\":1}").path("data").get(0);
        int racedAttempt = racedJob.path("attemptNo").asInt();
        long racedFence = racedJob.path("fencingToken").asLong();
        try (var invalidator = context.getBean(javax.sql.DataSource.class).getConnection()) {
            invalidator.setAutoCommit(false);
            try (var statement = invalidator.createStatement()) {
                statement.executeQuery("SELECT id FROM ai_job WHERE id=9005 FOR UPDATE").close();
                statement.executeUpdate("UPDATE ai_job_attempt SET finished_at=now() WHERE job_id=9005 AND attempt_no="
                        + racedAttempt);
                statement.executeUpdate("UPDATE ai_job SET status='QUEUED',claimed_by=NULL,claim_expires_at=NULL "
                        + "WHERE id=9005");
                var racedTicket = CompletableFuture.supplyAsync(() -> runtimePostUnchecked(port,
                        "/internal-api/design/v1/ai-jobs/9005/output-tickets",
                        "{\"attemptNo\":" + racedAttempt + ",\"fencingToken\":" + racedFence
                                + ",\"slot\":1,\"mimeType\":\"image/png\"}", 0));
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                while (System.nanoTime() < deadline && jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' "
                                + "AND query LIKE 'SELECT requested_count FROM ai_job%'", Integer.class) == 0) {
                    Thread.sleep(20L);
                }
                assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' "
                        + "AND query LIKE 'SELECT requested_count FROM ai_job%'", Integer.class)).isGreaterThan(0);
                invalidator.commit();
                assertThat(racedTicket.join().statusCode()).isEqualTo(409);
            } catch (Throwable failure) {
                invalidator.rollback();
                throw failure;
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_provider_output_ticket WHERE job_id=9005", Integer.class))
                .isZero();

        assertRuntimeRejected(port, "/internal-api/design/v1/ai-jobs/999999999999999999999999/result-events",
                "{\"attemptNo\":1,\"fencingToken\":1}", 400);
    }

    private static JsonNode runtimePost(int port, String path, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = runtimeSignature("test-only-runtime-secret", timestamp, path, bytes);
        var response = HttpClient.newHttpClient().send(runtimeSigned(port, path, bytes, timestamp, signature),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static void assertRuntimeRejected(int port, String path, String body, int expectedStatus) throws Exception {
        var response = runtimePostResponse(port, path, body, 0);
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(expectedStatus);
    }

    private static HttpResponse<String> runtimePostUnchecked(int port, String path, String body, long timestampOffset) {
        try {
            return runtimePostResponse(port, path, body, timestampOffset);
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    private static HttpResponse<String> runtimePostResponse(int port, String path, String body, long timestampOffset)
            throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(Instant.now().plusSeconds(timestampOffset).getEpochSecond());
        String signature = runtimeSignature("test-only-runtime-secret", timestamp, path, bytes);
        return HttpClient.newHttpClient().send(runtimeSigned(port, path, bytes, timestamp, signature),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest runtimeSigned(int port, String path, byte[] body, String timestamp, String signature) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-ZS-Timestamp", timestamp)
                .header("X-ZS-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }

    private static String runtimeSignature(String secret, String timestamp, String path, byte[] body) throws Exception {
        String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(
                (timestamp + "\nPOST\n" + path + "\n" + bodyHash).getBytes(StandardCharsets.UTF_8)));
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
                .locations("classpath:db/migration/platform", "classpath:db/migration/identity", "classpath:db/migration/design",
                        "classpath:db/migration/commerce", "classpath:db/migration/ai-orchestration",
                        "classpath:db/migration/design-capability", "classpath:db/migration/design-provider")
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

}
