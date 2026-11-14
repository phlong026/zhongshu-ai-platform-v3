package cn.iocoder.yudao.module.franchise.design.publiccase;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicCaseCatalogClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readsPublishedCaseDataWithTenantHeaderAndNoUserCredential() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> tenant = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        start(exchange -> {
            method.set(exchange.getRequestMethod());
            tenant.set(exchange.getRequestHeaders().getFirst("tenant-id"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getRawQuery());
            reply(exchange, 200, """
                    {"code":0,"msg":"","data":{"list":[{"id":7,"allowedActions":["VIEW","DESIGN_WITH"]}],"nextCursor":"c2"}}
                    """);
        });

        PublicCaseCatalogClient client = client(Duration.ofSeconds(2), 4096);
        var data = client.listCases(Map.of(
                "keyword", "暖 白", "limit", "20", "minArea", "80", "maxArea", "120"));

        assertEquals("GET", method.get());
        assertEquals("1", tenant.get());
        assertEquals(null, authorization.get());
        assertEquals("keyword=%E6%9A%96%20%E7%99%BD&limit=20&maxArea=120&minArea=80", query.get());
        assertEquals("DESIGN_WITH", data.path("list").get(0).path("allowedActions").get(1).asText());
        assertEquals("c2", data.path("nextCursor").asText());
    }

    @Test
    void usesOnlyFixedReadPaths() throws Exception {
        AtomicReference<String> paths = new AtomicReference<>("");
        start(exchange -> {
            paths.updateAndGet(current -> current + exchange.getRequestURI().getPath() + "\n");
            reply(exchange, 200, """
                    {"code":0,"msg":"","data":{"allowedActions":["VIEW"]}}
                    """);
        });

        PublicCaseCatalogClient client = client(Duration.ofSeconds(2), 4096);
        client.listStyles();
        var detail = client.getCase(19);

        assertEquals("/app-api/design/v1/cases/styles\n/app-api/design/v1/cases/19\n", paths.get());
        assertEquals("VIEW", detail.path("allowedActions").get(0).asText());
        assertThrows(IllegalArgumentException.class, () -> client.getCase(0));
        assertThrows(IllegalArgumentException.class,
                () -> client.listCases(Map.of("private", "true")));
        assertThrows(IllegalArgumentException.class,
                () -> client.listCases(Map.of("buildingArea", "100")));
    }

    @Test
    void productionSettingsRequireAPathlessHttpsOrigin() {
        assertThrows(IllegalArgumentException.class, () -> new PublicCaseCatalogSettings(
                URI.create("http://api.example.test"), "1", Duration.ofSeconds(1), Duration.ofSeconds(2), 4096));
        assertThrows(IllegalArgumentException.class, () -> new PublicCaseCatalogSettings(
                URI.create("https://api.example.test/app-api"), "1",
                Duration.ofSeconds(1), Duration.ofSeconds(2), 4096));
        assertThrows(IllegalArgumentException.class, () -> new PublicCaseCatalogSettings(
                URI.create("https://api.example.test"), "0", Duration.ofSeconds(1), Duration.ofSeconds(2), 4096));
    }

    @Test
    void exposesUpstreamBusinessFailureInsteadOfReturningEmptyData() throws Exception {
        start(exchange -> reply(exchange, 200, """
                {"code":400,"msg":"请求的租户标识未传递","data":null}
                """));

        PublicCaseCatalogException error = assertThrows(PublicCaseCatalogException.class,
                () -> client(Duration.ofSeconds(2), 4096).listStyles());

        assertEquals(400, error.upstreamCode());
        assertTrue(error.getMessage().contains("公开案例服务拒绝请求"));
        assertFalse(error.getMessage().contains(serverOrigin().toString()));
    }

    @Test
    void logsOneSanitizedWarningForAnUpstreamFailure() throws Exception {
        start(exchange -> reply(exchange, 200, """
                {"code":400,"msg":"secret upstream at https://private.example","data":null}
                """));
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PublicCaseCatalogClient.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertThrows(PublicCaseCatalogException.class,
                    () -> client(Duration.ofSeconds(2), 4096)
                            .listCases(Map.of("keyword", "private-keyword")));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertEquals(1, appender.list.size());
        ILoggingEvent warning = appender.list.get(0);
        assertEquals(Level.WARN, warning.getLevel());
        assertEquals("LIST", warning.getArgumentArray()[0].toString());
        assertEquals("UPSTREAM_BUSINESS", warning.getArgumentArray()[1].toString());
        assertEquals(200, warning.getArgumentArray()[2]);
        assertEquals(400, warning.getArgumentArray()[3]);
        assertTrue(((Long) warning.getArgumentArray()[4]) >= 0);
        assertEquals("NONE", warning.getArgumentArray()[5].toString());
        String rendered = warning.getFormattedMessage();
        assertFalse(rendered.contains("private-keyword"));
        assertFalse(rendered.contains("private.example"));
        assertFalse(rendered.contains(serverOrigin().toString()));
        assertFalse(rendered.contains("tenant"));
    }

    @Test
    void malformedJsonIsLoggedAsInvalidResponseRatherThanTransportFailure() throws Exception {
        start(exchange -> reply(exchange, 200, "{not-json from https://private.example"));
        ILoggingEvent warning = captureSingleWarning(() ->
                assertThrows(PublicCaseCatalogException.class,
                        () -> client(Duration.ofSeconds(2), 4096).listStyles()));

        assertEquals("INVALID_RESPONSE", warning.getArgumentArray()[1].toString());
        assertEquals(200, warning.getArgumentArray()[2]);
        assertEquals("NONE", warning.getArgumentArray()[5].toString());
        assertFalse(warning.getFormattedMessage().contains("private.example"));
    }

    @Test
    void emptyResponseBodyIsLoggedAsInvalidResponse() throws Exception {
        assertInvalidEnvelope("");
    }

    @Test
    void jsonNullIsLoggedAsInvalidResponse() throws Exception {
        assertInvalidEnvelope("null");
    }

    @Test
    void nonObjectJsonIsLoggedAsInvalidResponse() throws Exception {
        assertInvalidEnvelope("[]");
    }

    @Test
    void transportFailureLogsOnlyAWhitelistedCauseCategory() throws Exception {
        start(exchange -> reply(exchange, 200, "{}"));
        URI stoppedOrigin = serverOrigin();
        server.stop(0);

        ILoggingEvent warning = captureSingleWarning(() -> {
            PublicCaseCatalogSettings settings = PublicCaseCatalogSettings.forLoopbackTests(
                    stoppedOrigin, "1", Duration.ofMillis(100), Duration.ofSeconds(1), 4096);
            assertThrows(PublicCaseCatalogException.class,
                    () -> new PublicCaseCatalogClient(settings, new ObjectMapper()).listStyles());
        });

        assertEquals("TRANSPORT", warning.getArgumentArray()[1].toString());
        assertEquals("CONNECT", warning.getArgumentArray()[5].toString());
        assertFalse(warning.getFormattedMessage().contains(stoppedOrigin.toString()));
    }

    @Test
    void transportCauseMappingUsesOnlyFiniteTypeBasedCategories() {
        assertEquals(PublicCaseCatalogClient.TransportCause.DNS,
                PublicCaseCatalogClient.transportCause(
                        new IOException("wrapper", new UnknownHostException("sensitive-host"))));
        assertEquals(PublicCaseCatalogClient.TransportCause.TLS,
                PublicCaseCatalogClient.transportCause(
                        new IOException("wrapper", new SSLException("sensitive-certificate"))));
        assertEquals(PublicCaseCatalogClient.TransportCause.RESET,
                PublicCaseCatalogClient.transportCause(new SocketException("sensitive-reset-detail")));
        assertEquals(PublicCaseCatalogClient.TransportCause.OTHER,
                PublicCaseCatalogClient.transportCause(new IllegalStateException("sensitive-runtime-detail")));
    }

    @Test
    void boundsResponseSizeAndTimeoutWithoutLeakingOrigin() throws Exception {
        start(exchange -> reply(exchange, 200, """
                {"code":0,"msg":"","data":{"padding":"012345678901234567890123456789"}}
                """));
        PublicCaseCatalogException oversized = assertThrows(PublicCaseCatalogException.class,
                () -> client(Duration.ofSeconds(2), 32).listStyles());
        assertTrue(oversized.getMessage().contains("响应过大"));
        assertFalse(oversized.getMessage().contains(serverOrigin().toString()));

        server.stop(0);
        server = null;
        start(exchange -> {
            try {
                Thread.sleep(300);
                reply(exchange, 200, "{\"code\":0,\"data\":[]}");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        PublicCaseCatalogException timedOut = assertThrows(PublicCaseCatalogException.class,
                () -> client(Duration.ofMillis(50), 4096).listStyles());
        assertTrue(timedOut.getMessage().contains("暂不可用"));
        assertFalse(timedOut.getMessage().contains(serverOrigin().toString()));
    }

    @Test
    void completeBodyMustArriveWithinTheSingleRequestDeadline() throws Exception {
        CountDownLatch releaseBody = new CountDownLatch(1);
        start(exchange -> {
            byte[] prefix = "{\"code\":0,\"data\":\"".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write(prefix);
                output.flush();
                try {
                    releaseBody.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                output.write("done\"}".getBytes(StandardCharsets.UTF_8));
            }
        });

        try {
            assertTimeoutPreemptively(Duration.ofMillis(700), () ->
                    assertThrows(PublicCaseCatalogException.class,
                            () -> client(Duration.ofMillis(75), 4096).listStyles()));
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void callerRemainingDeadlineShortensConfiguredTimeoutAndCancelsBodyRead() throws Exception {
        CountDownLatch releaseBody = new CountDownLatch(1);
        start(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write("{\"code\":0,\"data\":{".getBytes(StandardCharsets.UTF_8));
                output.flush();
                releaseBody.await(2, TimeUnit.SECONDS);
                output.write("\"caseId\":\"19\"}}".getBytes(StandardCharsets.UTF_8));
            } catch (IOException cancelled) {
                // Depending on kernel buffering the server may or may not observe the client cancellation.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });

        ILoggingEvent warning = captureSingleWarning(() -> {
            try {
                assertTimeoutPreemptively(Duration.ofMillis(700), () ->
                        assertThrows(PublicCaseCatalogException.class,
                                () -> client(Duration.ofSeconds(2), 4096)
                                        .getCase(19, Duration.ofMillis(75))));
            } finally {
                releaseBody.countDown();
            }
        });
        assertEquals("TIMEOUT", warning.getArgumentArray()[1].toString());
    }

    private PublicCaseCatalogClient client(Duration timeout, int maxResponseBytes) {
        PublicCaseCatalogSettings settings = PublicCaseCatalogSettings.forLoopbackTests(
                serverOrigin(), "1", Duration.ofMillis(50), timeout, maxResponseBytes);
        return new PublicCaseCatalogClient(settings, new ObjectMapper());
    }

    private static ILoggingEvent captureSingleWarning(Runnable action) {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PublicCaseCatalogClient.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        assertEquals(1, appender.list.size());
        return appender.list.get(0);
    }

    private void assertInvalidEnvelope(String body) throws Exception {
        start(exchange -> reply(exchange, 200, body));
        ILoggingEvent warning = captureSingleWarning(() ->
                assertThrows(PublicCaseCatalogException.class,
                        () -> client(Duration.ofSeconds(2), 4096).listStyles()));
        assertEquals("INVALID_RESPONSE", warning.getArgumentArray()[1].toString());
        assertEquals(200, warning.getArgumentArray()[2]);
        assertEquals("NONE", warning.getArgumentArray()[5].toString());
    }

    private URI serverOrigin() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void start(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
    }

    private static void reply(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
