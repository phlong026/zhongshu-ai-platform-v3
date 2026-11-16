package cn.iocoder.yudao.module.franchise.design.publiccase;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.slf4j.LoggerFactory;

@Testcontainers
class PublicCaseAccessServiceTest {

    @Container
    static final PostgreSQLContainer<?> PG = cn.iocoder.yudao.module.platform.test.TestPostgres.database("postgres:17-alpine");

    private HttpServer server;
    private JdbcTemplate jdbc;
    private PublicCaseAccessService access;
    private PublicCaseAdmission admission;
    private Consumer<HttpExchange> handler;
    private final AtomicInteger detailGets = new AtomicInteger();
    private java.util.concurrent.ExecutorService httpExecutor;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = new SimpleDriverDataSource(new org.postgresql.Driver(),
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        var flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).baselineOnMigrate(true)
                .locations("classpath:db/migration/franchise-design-remote").load();
        flyway.clean();
        new JdbcTemplate(dataSource).execute("""
                CREATE TABLE system_menu (
                    id int8 PRIMARY KEY, name varchar(64), permission varchar(128), type int4, sort int4,
                    parent_id int8, path varchar(256), icon varchar(128), component varchar(256),
                    component_name varchar(128), status int4, visible bool, keep_alive bool, always_show bool,
                    creator varchar(64), updater varchar(64))
                """);
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handler.accept(exchange));
        httpExecutor = Executors.newCachedThreadPool();
        server.setExecutor(httpExecutor);
        server.start();
        var settings = PublicCaseCatalogSettings.forLoopbackTests(
                java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "1",
                Duration.ofSeconds(1), Duration.ofSeconds(2), 64 * 1024);
        var mapper = new ObjectMapper();
        admission = new PublicCaseAdmission(4, 2);
        access = new PublicCaseAccessService(new PublicCaseCatalogClient(settings, mapper), mapper,
                dataSource, admission, Duration.ofSeconds(2), 2, 64);
        handler = this::publishedCaseResponse;
        TenantContextHolder.setTenantId(31L);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        if (access != null) access.close();
        if (server != null) server.stop(0);
        if (httpExecutor != null) httpExecutor.shutdownNow();
    }

    @Test
    void publicReadsPreserveAuthoritativeDesignWithActionFromListsAndDetail() {
        handler = exchange -> {
            try {
                assertThat(exchange.getRequestMethod()).isEqualTo("GET");
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
                assertThat(exchange.getRequestHeaders().getFirst("tenant-id")).isEqualTo("1");
                if (exchange.getRequestURI().getPath().endsWith("/styles")) {
                    reply(exchange, 200, """
                            {"code":0,"data":[{"code":"MODERN","label":"现代"}]}
                            """);
                } else if (exchange.getRequestURI().getPath().endsWith("/7")) {
                    reply(exchange, 200, detail("7", "[​\"FAVORITE\",\"DESIGN_WITH\"]"));
                } else {
                    reply(exchange, 200, """
                            {"code":0,"data":{"list":[{"caseId":"7","title":"山居","allowedActions":["VIEW","DESIGN_WITH","FAVORITE"]}],"nextCursor":"a-next"}}
                            """);
                }
            } catch (IOException error) {
                throw new RuntimeException(error);
            }
        };
        PostSnapshot post = post(10, 101);

        var page = access.listCases(post, new DesignServicePort.CaseQuery(
                "MODERN", 2, new java.math.BigDecimal("8.5"), new java.math.BigDecimal("10"),
                "山居", "a-cursor", 20));
        var styles = access.listCaseStyles(post);
        var detail = access.getCaseDetail(post, 7);

        assertThat(page.nextCursor()).isEqualTo("a-next");
        assertThat(page.list().get(0)).containsEntry("caseId", "7")
                .containsEntry("allowedActions", java.util.List.of("VIEW", "DESIGN_WITH", "FAVORITE"));
        assertThat(styles).containsExactly(new DesignServicePort.StyleOption("MODERN", "现代"));
        assertThat(detail.fields()).containsEntry("caseId", "7")
                .containsEntry("allowedActions", java.util.List.of("FAVORITE", "DESIGN_WITH"));
    }

    @Test
    void favoriteIsIdempotentAndWritesOnlyTenantOrganizationEmployeeScope() {
        PostSnapshot employee = post(10, 101);

        access.favoriteCase(employee, 7);
        access.favoriteCase(employee, 7);

        assertThat(detailGets).hasValue(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_design_case_favorite", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT tenant_id,org_id,employee_user_id,external_case_id FROM fr_design_case_favorite"))
                .containsEntry("tenant_id", 31L).containsEntry("org_id", 10L)
                .containsEntry("employee_user_id", 101L).containsEntry("external_case_id", 7L);
    }

    @Test
    void favoritesAreIsolatedAcrossTenantOrganizationAndEmployeeAndHydratedLive() {
        access.favoriteCase(post(10, 101), 7);
        access.favoriteCase(post(10, 102), 8);
        access.favoriteCase(post(11, 101), 9);
        TenantContextHolder.setTenantId(32L);
        access.favoriteCase(post(10, 101), 10);

        TenantContextHolder.setTenantId(31L);
        var first = access.listFavorites(post(10, 101), null, 20);
        var otherEmployee = access.listFavorites(post(10, 102), null, 20);
        var otherOrganization = access.listFavorites(post(11, 101), null, 20);
        TenantContextHolder.setTenantId(32L);
        var otherTenant = access.listFavorites(post(10, 101), null, 20);

        assertThat(first.list()).extracting(item -> item.get("caseId")).containsExactly("7");
        assertThat(otherEmployee.list()).extracting(item -> item.get("caseId")).containsExactly("8");
        assertThat(otherOrganization.list()).extracting(item -> item.get("caseId")).containsExactly("9");
        assertThat(otherTenant.list()).extracting(item -> item.get("caseId")).containsExactly("10");
        assertThat(first.list().get(0)).containsEntry("allowedActions", java.util.List.of("FAVORITE", "DESIGN_WITH"));
    }

    @Test
    void unpublishedOrUnavailableCaseIsNeverInsertedOrReturnedAsStaleSnapshot() {
        handler = exchange -> {
            try {
                reply(exchange, 200, "{\"code\":403,\"msg\":\"not published\",\"data\":null}");
            } catch (IOException error) {
                throw new RuntimeException(error);
            }
        };
        assertThatThrownBy(() -> access.favoriteCase(post(10, 101), 404))
                .isInstanceOf(ServiceException.class).hasMessageContaining("公开案例服务");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_design_case_favorite", Integer.class)).isZero();

        handler = this::publishedCaseResponse;
        access.favoriteCase(post(10, 101), 7);
        handler = exchange -> {
            try {
                reply(exchange, 503, "{}");
            } catch (IOException error) {
                throw new RuntimeException(error);
            }
        };
        assertThatThrownBy(() -> access.listFavorites(post(10, 101), null, 20))
                .isInstanceOf(ServiceException.class).hasMessageContaining("公开案例服务");
    }

    @Test
    void unfavoriteWorksWhenAIsDownAndCannotDeleteAnotherScope() {
        access.favoriteCase(post(10, 101), 7);
        handler = exchange -> {
            throw new AssertionError("取消收藏不应请求 A");
        };

        access.unfavoriteCase(post(11, 101), 7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_design_case_favorite", Integer.class)).isEqualTo(1);
        access.unfavoriteCase(post(10, 101), 7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_design_case_favorite", Integer.class)).isZero();
    }

    @Test
    void undoRefusesNonemptyFavoritesAndDropsOnlyAnEmptyBTable() throws Exception {
        access.favoriteCase(post(10, 101), 7);
        String undo;
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration-undo/franchise-design-remote/V20261114.915__undo_local_public_case_favorites.sql")) {
            assertThat(input).isNotNull();
            undo = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThatThrownBy(() -> executeSql(undo))
                .hasMessageContaining("U915_FAVORITES_EXIST");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_design_case_favorite", Integer.class)).isEqualTo(1);

        jdbc.update("DELETE FROM fr_design_case_favorite");
        executeSql(undo);
        assertThat(jdbc.queryForObject("SELECT to_regclass('fr_design_case_favorite')", String.class)).isNull();
    }

    @Test
    void favoritePageHydratesWithBoundedParallelismAndKeepsOrder() {
        for (long id = 1; id <= 6; id++) insertFavorite(id, id);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        handler = exchange -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(120);
                publishedCaseResponse(exchange);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
        };

        long started = System.nanoTime();
        var page = access.listFavorites(post(10, 101), null, 20);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(maxActive).hasValue(2);
        assertThat(elapsedMs).isLessThan(900);
        assertThat(page.list()).extracting(item -> item.get("caseId"))
                .containsExactly("6", "5", "4", "3", "2", "1");
    }

    @Test
    void favoritePageHasOneTotalDeadlineCancelsOutstandingHttpAndUsesStableCode() throws Exception {
        access.close();
        var mapper = new ObjectMapper();
        var settings = PublicCaseCatalogSettings.forLoopbackTests(
                java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "1",
                Duration.ofSeconds(1), Duration.ofSeconds(2), 64 * 1024);
        access = new PublicCaseAccessService(new PublicCaseCatalogClient(settings, mapper), mapper,
                jdbc.getDataSource(), new PublicCaseAdmission(4, 2), Duration.ofMillis(150), 2, 64);
        for (long id = 1; id <= 6; id++) insertFavorite(id, id);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        handler = exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                var output = exchange.getResponseBody();
                output.write("{\"code\":0,\"data\":{".getBytes(StandardCharsets.UTF_8));
                output.flush();
                started.countDown();
                release.await(2, TimeUnit.SECONDS);
                output.write("\"caseId\":\"1\"}}".getBytes(StandardCharsets.UTF_8));
                output.close();
            } catch (IOException cancelled) {
                // Kernel buffering determines whether the test server observes the reset.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };

        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PublicCaseCatalogClient.class);
        CountDownLatch cancelledHttp = new CountDownLatch(1);
        var appender = new ListAppender<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                super.append(event);
                if (event.getArgumentArray() != null && event.getArgumentArray().length > 1
                        && java.util.Set.of("INTERRUPTED", "TIMEOUT")
                        .contains(event.getArgumentArray()[1].toString())) {
                    cancelledHttp.countDown();
                }
            }
        };
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> access.listFavorites(post(10, 101), null, 20))
                    .isInstanceOfSatisfying(ServiceException.class, error ->
                            assertThat(error.getCode()).isEqualTo(PublicCaseAccessService.PUBLIC_CASE_PAGE_TIMEOUT));
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(cancelledHttp.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void upstreamAndShapeFailuresUseStableBusinessCodes() {
        handler = exchange -> {
            try {
                reply(exchange, 503, "{}");
            } catch (IOException error) {
                throw new RuntimeException(error);
            }
        };
        assertThatThrownBy(() -> access.getCaseDetail(post(10, 101), 7))
                .isInstanceOfSatisfying(ServiceException.class, error ->
                        assertThat(error.getCode()).isEqualTo(PublicCaseAccessService.PUBLIC_CASE_UNAVAILABLE));
        handler = exchange -> {
            try {
                reply(exchange, 200, "{\"code\":0,\"data\":[]}");
            } catch (IOException error) {
                throw new RuntimeException(error);
            }
        };
        assertThatThrownBy(() -> access.getCaseDetail(post(10, 101), 7))
                .isInstanceOfSatisfying(ServiceException.class, error ->
                        assertThat(error.getCode()).isEqualTo(PublicCaseAccessService.PUBLIC_CASE_INVALID_RESPONSE));
        handler = exchange -> {
            try {
                reply(exchange, 200, "{broken-json");
            } catch (IOException error) {
                throw new RuntimeException(error);
            }
        };
        assertThatThrownBy(() -> access.getCaseDetail(post(10, 101), 7))
                .isInstanceOfSatisfying(ServiceException.class, error ->
                        assertThat(error.getCode()).isEqualTo(PublicCaseAccessService.PUBLIC_CASE_INVALID_RESPONSE));
    }

    @Test
    void tenantOrganizationAdmissionFailureUsesStableBusyCode() {
        try (var first = admission.enter(31, 10); var second = admission.enter(31, 10)) {
            assertThatThrownBy(() -> access.getCaseDetail(post(10, 101), 7))
                    .isInstanceOfSatisfying(ServiceException.class, error ->
                            assertThat(error.getCode()).isEqualTo(PublicCaseAccessService.PUBLIC_CASE_BUSY));
        }
    }

    private void insertFavorite(long id, long caseId) {
        jdbc.update("""
                INSERT INTO fr_design_case_favorite(id,tenant_id,org_id,employee_user_id,external_case_id)
                VALUES (?,31,10,101,?)
                """, id, caseId);
    }

    private void executeSql(String sql) throws Exception {
        try (var connection = jdbc.getDataSource().getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void publishedCaseResponse(HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI().getPath();
            long id = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
            detailGets.incrementAndGet();
            reply(exchange, 200, detail(Long.toString(id), "[\"FAVORITE\",\"DESIGN_WITH\"]"));
        } catch (IOException error) {
            throw new RuntimeException(error);
        }
    }

    private static String detail(String id, String allowedActions) {
        return "{\"code\":0,\"data\":{" +
                "\"caseId\":\"" + id + "\",\"caseVersionId\":\"70\",\"title\":\"山居" + id + "\"," +
                "\"sourceType\":\"COMPANY\",\"parameters\":{\"styleCode\":\"MODERN\",\"floorCount\":2," +
                "\"buildingArea\":160,\"faceWidth\":8.5,\"depth\":10},\"coverAssetId\":\"700\"," +
                "\"uploaderName\":\"张三\",\"uploaderCompany\":\"众墅\",\"allowedActions\":" + allowedActions + "}}";
    }

    private static PostSnapshot post(long orgId, long userId) {
        return new PostSnapshot(userId + 1000, userId, orgId, "STORE", "DESIGNER", null, false, Set.of());
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.replace("\u200b", "").getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
