package cn.iocoder.yudao.module.design.budget;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiDesignBaselineSourceTest {
    @TempDir Path directory;
    HttpServer server;
    Path token;
    AtomicInteger calls = new AtomicInteger();
    List<String> queries = new ArrayList<>();
    Function<String, String> response;
    int status = 200;
    boolean failOnePriceRead = false;
    final Instant at = Instant.parse("2026-11-16T10:00:00Z");
    @BeforeEach void setup() throws Exception {
        token = directory.resolve("source-token"); Files.writeString(token, "test-source-token");
        response = query -> page(1, price("201", "301", 52000, "2026-11-01T00:00:00Z", null));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/admin-api/design/v1/budget/prices", exchange -> {
            calls.incrementAndGet(); queries.add(exchange.getRequestURI().getQuery());
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-source-token");
            assertThat(exchange.getRequestHeaders().getFirst("tenant-id")).isEqualTo("1");
            byte[] bytes = response.apply(exchange.getRequestURI().getQuery()).getBytes(StandardCharsets.UTF_8);
            int httpStatus = failOnePriceRead ? 401 : status; failOnePriceRead = false;
            exchange.sendResponseHeaders(httpStatus, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    AiDesignBaselineSource source() { return new AiDesignBaselineSource("http://127.0.0.1:" + server.getAddress().getPort(), token.toString(), "1", new ObjectMapper()); }
    static String page(int total, String... rows) { return "{\"code\":0,\"data\":{\"total\":"+total+",\"list\":["+String.join(",", rows)+"]}}"; }
    static String price(String option, String id, long cents, String start, String end) {
        return "{\"optionId\":\""+option+"\",\"priceId\":\""+id+"\",\"regionCode\":\"WUHAN\",\"status\":\"PUBLISHED\",\"unitPriceCents\":"+cents+",\"version\":3,\"effectiveAt\":\""+start+"\",\"expiresAt\":"+(end==null?"null":"\""+end+"\"")+"}";
    }
    @Test void existingApiAndSameCalculationSnapshotAreReused() {
        var source = source(); assertThat(source.published("WUHAN", at).get(201L).cents()).isEqualTo(52000);
        source.published("WUHAN", at); assertThat(calls).hasValue(1);
        assertThat(queries.get(0)).contains("regionCode=WUHAN", "status=PUBLISHED", "pageNo=1");
        response = query -> page(1, price("201", "302", 61000, "2026-11-01T00:00:00Z", null));
        assertThat(source.published("WUHAN", at.plusSeconds(1)).get(201L).cents()).isEqualTo(61000);
        assertThat(source.published("WUHAN", at).get(201L).cents()).isEqualTo(52000);
    }
    @Test void allPagesAndOriginalLongIdsArePreserved() {
        response = query -> query.contains("pageNo=1") ? page(2, price("201", "301", 100, "2026-11-01T00:00:00Z", null))
            : page(2, price("2122156666820575234", "2122156666820575235", 200, "2026-11-01T00:00:00Z", null));
        assertThat(source().published("WUHAN", at)).containsKeys(201L, 2122156666820575234L); assertThat(calls).hasValue(2);
    }
    @Test void unpublishedWindowsAndExpiredRowsNeverBecomeCurrentPrices() {
        response = query -> page(3, price("201", "301", 100, "2026-12-01T00:00:00Z", null),
            price("202", "302", 100, "2026-11-01T00:00:00Z", "2026-11-16T10:00:00Z"),
            price("203", "303", 100, "2026-11-16T10:00:00Z", null));
        assertThat(source().published("WUHAN", at)).containsOnlyKeys(203L);
    }
    @Test void failedSourceIsNotReplacedWithAnOldSnapshotAndCanRetry() {
        var source = source(); status = 401;
        assertThatThrownBy(() -> source.published("WUHAN", at)).isInstanceOf(ServiceException.class).hasMessageNotContaining("test-source-token");
        status = 200; assertThat(source.published("WUHAN", at)).hasSize(1); assertThat(calls).hasValue(2);
    }
    @Test void duplicateOrWrongRegionAndMalformedPricesAreRejected() {
        response = query -> page(2, price("201", "301", 100, "2026-11-01T00:00:00Z", null), price("201", "302", 100, "2026-11-01T00:00:00Z", null));
        assertThatThrownBy(() -> source().published("WUHAN", at)).isInstanceOf(ServiceException.class);
        response = query -> page(1, price("201", "301", 100, "2026-11-01T00:00:00Z", null).replace("WUHAN", "OTHER"));
        assertThatThrownBy(() -> source().published("WUHAN", at)).isInstanceOf(ServiceException.class);
        response = query -> page(1, price("201", "301", -100, "2026-11-01T00:00:00Z", null));
        assertThatThrownBy(() -> source().published("WUHAN", at)).isInstanceOf(ServiceException.class);
    }
    @Test void missingBaselineRemainsMissingAndZeroNeedsExplicitFreeReason() {
        response = query -> page(0); assertThat(source().published("WUHAN", at)).isEmpty();
        response = query -> page(1, price("201", "301", 0, "2026-11-01T00:00:00Z", null));
        assertThatThrownBy(() -> source().published("WUHAN", at)).isInstanceOf(ServiceException.class);
    }
    @Test void resolverReadsSourceAndNeverQueriesTheCopiedPriceTable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(42L))).thenReturn("WUHAN");
        var resolver = new BudgetPriceResolver(jdbc); resolver.setBaselineSource(source());
        var region = new BudgetPriceResolver.Region(42, "WUHAN", "武汉", 1, false, "ACTIVE", null, null, null);
        var price = resolver.price(9, region, 201, at);
        assertThat(price.unitPriceCents()).isEqualTo(52000); assertThat(price.priceVersionId()).isEqualTo(301);
        assertThat(resolver.price(9, region, 999, at).unitPriceCents()).isNull();
        assertThat(mockingDetails(jdbc).getInvocations()).allSatisfy(invocation -> {
            if (invocation.getArguments().length > 0) assertThat(String.valueOf(invocation.getArgument(0, Object.class))).doesNotContain("FROM budget_item_price");
        });
    }
    @Test void accountPriceViewUsesSourceBaselineButKeepsTheAccountOverride() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            assertThat(sql).doesNotContain("FROM budget_item_price");
            if (sql.contains("FROM budget_region")) return List.of(new LinkedHashMap<>(Map.of("id", 42L, "code", "WUHAN", "name", "武汉")));
            Map<String,Object> row = new LinkedHashMap<>();
            row.putAll(Map.of("item_id", 1L, "item_code", "FOUNDATION", "item_name", "地基基础", "category", "BODY",
                "option_id", 201L, "option_code", "A", "label", "条基", "selection_group", "DEFAULT", "unit", "SQM"));
            row.put("my_id", 800L); row.put("my_cents", 70000L); return List.of(row);
        });
        var service = new BudgetAccountPriceService(mock(javax.sql.DataSource.class),
            mock(org.springframework.transaction.PlatformTransactionManager.class), mock(cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "jdbc", jdbc);
        service.setBaselineSource(source());
        var option = service.listPrices(9, "WUHAN").groups().get(0).options().get(0);
        assertThat(option.baselinePriceCents()).isEqualTo(52000); assertThat(option.myPriceCents()).isEqualTo(70000);
        assertThat(option.baselinePriceVersion()).isEqualTo(3);
    }
    @Test void oauthServiceAccountAutomaticallyRefreshesUsingExistingApi() throws Exception {
        checkOauth(false);
    }
    @Test void revokedRefreshReauthenticatesOnlyTheReadonlyServiceAccount() throws Exception {
        checkOauth(true);
    }
    void checkOauth(boolean rejectRefresh) throws Exception {
        Path credentials = directory.resolve("source-credentials.json");
        Files.writeString(credentials, "{\"username\":\"reader\",\"password\":\"test-password\",\"clientId\":\"readonly-client\",\"clientSecret\":\"test-client-secret\"}");
        List<String> forms = new ArrayList<>();
        server.createContext("/admin-api/system/oauth2/token", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).startsWith("Basic ");
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); forms.add(form);
            boolean rejected = rejectRefresh && form.contains("grant_type=refresh_token");
            byte[] body = (rejected ? "{\"code\":401}" : "{\"code\":0,\"data\":{\"access_token\":\"test-source-token\",\"refresh_token\":\"test-refresh\",\"expires_in\":3600}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(rejected ? 401 : 200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        var source = new AiDesignBaselineSource("http://127.0.0.1:" + server.getAddress().getPort(), "", credentials.toString(), "1", new ObjectMapper());
        assertThat(source.published("WUHAN", at)).hasSize(1);
        failOnePriceRead = true;
        assertThat(source.published("WUHAN", at.plusSeconds(1))).hasSize(1);
        assertThat(forms).hasSize(rejectRefresh ? 3 : 2);
        assertThat(forms.get(0)).contains("grant_type=password", "scope=budget.read");
        assertThat(forms.get(1)).contains("grant_type=refresh_token");
    }
    @Test void configurationRejectsRemotePlainHttpAndEmbeddedCredentials() {
        for (String url : List.of("http://example.com", "https://user:password@example.com", "https://example.com/path", "https://example.com?q=x"))
            assertThatThrownBy(() -> new AiDesignBaselineSource(url, token.toString(), "1", new ObjectMapper())).isInstanceOf(IllegalArgumentException.class);
    }
}
