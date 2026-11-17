package cn.iocoder.yudao.module.design.budget;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Reads A's existing published-price API. Never copies or authors a baseline price in B. */
@Component
@ConditionalOnProperty(name = "zhongshu.design.budget-source.enabled", havingValue = "true")
public class AiDesignBaselineSource {
    private final URI base;
    private final String tokenFile;
    private final String credentialsFile;
    private volatile Token token;
    private record Token(String access, String refresh, Instant expires) {}
    private final String tenant;
    private final ObjectMapper json;
    private final HttpClient client;
    private record Key(String region, Instant at) {}
    // A single calculation shares a source snapshot; a different calculation always reads A again.
    private final Map<Key, Map<Long, Published>> reads = new ConcurrentHashMap<>();
    public record Published(long optionId, long priceId, long cents, int version,
                            String freeReason, Instant effectiveAt, Instant expiresAt) {
        BudgetPriceResolver.Price asPrice(String source) {
            return new BudgetPriceResolver.Price(cents, source, null, null, priceId, version, version,
                cents, null, freeReason, null, null, effectiveAt.toString(), expiresAt == null ? null : expiresAt.toString());
        }
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AiDesignBaselineSource(
            @Value("${zhongshu.design.budget-source.base-url:https://api.zszhj.com}") String baseUrl,
            @Value("${zhongshu.design.budget-source.token-file:}") String tokenFile,
            @Value("${zhongshu.design.budget-source.credentials-file:}") String credentialsFile,
            @Value("${zhongshu.design.budget-source.tenant-id:1}") String tenant,
            ObjectMapper json) {
        this.base = URI.create(baseUrl.replaceAll("/+$", ""));
        boolean loopback = "http".equals(base.getScheme()) && ("127.0.0.1".equals(base.getHost()) || "localhost".equals(base.getHost()));
        if ((!"https".equals(base.getScheme()) && !loopback) || base.getHost() == null || base.getUserInfo() != null
                || (base.getPath() != null && !base.getPath().isEmpty()) || base.getQuery() != null || base.getFragment() != null
                || !tenant.matches("[1-9][0-9]*") || ((tokenFile == null || tokenFile.isBlank()) && (credentialsFile == null || credentialsFile.isBlank())))
            throw new IllegalArgumentException("Invalid AI design budget source configuration");
        this.tokenFile = tokenFile; this.credentialsFile = credentialsFile; this.tenant = tenant; this.json = json;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public AiDesignBaselineSource(String baseUrl, String tokenFile, String tenant, ObjectMapper json) {
        this(baseUrl, tokenFile, "", tenant, json);
    }
    private synchronized String accessToken(boolean force) throws Exception {
        if (credentialsFile == null || credentialsFile.isBlank()) {
            String value = Files.readString(Path.of(tokenFile), StandardCharsets.UTF_8).trim();
            if (value.isBlank() || value.contains("\n") || value.contains("\r")) throw unavailable();
            return value;
        }
        if (!force && token != null && token.expires().isAfter(Instant.now().plusSeconds(60))) return token.access();
        JsonNode credentials = json.readTree(Files.readString(Path.of(credentialsFile), StandardCharsets.UTF_8));
        String id = required(credentials, "clientId"), secret = required(credentials, "clientSecret");
        if (token != null && token.refresh() != null) {
            try { token = grant(id, secret, "grant_type=refresh_token&refresh_token=" + encode(token.refresh())); return token.access(); }
            catch (ServiceException expired) { token = null; }
        }
        token = grant(id, secret, "grant_type=password&username=" + encode(required(credentials, "username"))
            + "&password=" + encode(required(credentials, "password")) + "&scope=budget.read");
        return token.access();
    }
    private Token grant(String id, String secret, String form) throws Exception {
        String basic = java.util.Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(base.resolve("/admin-api/system/oauth2/token"))
            .timeout(Duration.ofSeconds(10)).header("Authorization", "Basic " + basic).header("tenant-id", tenant)
            .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200 || response.body().length() > 100_000) throw unavailable();
        JsonNode body = json.readTree(response.body());
        if (body.path("code").asInt(-1) != 0) throw unavailable();
        JsonNode data = body.path("data");
        String access = required(data, "access_token");
        if (data.path("expires_in").asLong(0) < 1) throw unavailable();
        return new Token(access, data.path("refresh_token").asText(null), Instant.now().plusSeconds(data.path("expires_in").asLong()));
    }
    private JsonNode readPage(String path) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + accessToken(attempt > 0)).header("tenant-id", tenant).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.body().length() > 4_000_000) throw unavailable();
            JsonNode body = response.statusCode() == 200 ? json.readTree(response.body()) : json.createObjectNode().put("code", response.statusCode());
            if (body.path("code").asInt(-1) == 401 && credentialsFile != null && !credentialsFile.isBlank() && attempt == 0) continue;
            if (response.statusCode() != 200 || !body.path("code").canConvertToInt() || body.path("code").asInt(-1) != 0) throw unavailable();
            return body.path("data");
        }
        throw unavailable();
    }
    private static String required(JsonNode json, String key) {
        String value = json.path(key).asText("");
        if (value.isBlank() || value.contains("\r") || value.contains("\n")) throw unavailable();
        return value;
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    public Map<Long, Published> published(String region, Instant at) {
        if (region == null || !region.matches("[A-Za-z0-9_-]{1,32}") || at == null) throw new IllegalArgumentException("Invalid budget source region");
        if (reads.size() > 128) reads.keySet().removeIf(key -> key.at().isBefore(Instant.now().minusSeconds(60)));
        return reads.computeIfAbsent(new Key(region, at), key -> fetch(key.region(), key.at()));
    }
    private Map<Long, Published> fetch(String region, Instant at) {
        try {
            Map<Long, Published> result = new LinkedHashMap<>();
            int seen = 0;
            for (int page = 1; page <= 100; page++) {
                // This is the existing AI-design admin query, with its normal read-only permission check.
                String path = "/admin-api/design/v1/budget/prices?regionCode=" + URLEncoder.encode(region, StandardCharsets.UTF_8)
                    + "&status=PUBLISHED&pageSize=100&pageNo=" + page;
                JsonNode data = readPage(path), rows = data.path("list");
                if (!rows.isArray() || !data.path("total").canConvertToInt() || data.path("total").asInt(-1) < 0) throw unavailable();
                for (JsonNode row : rows) {
                    seen++;
                    if (!region.equals(row.path("regionCode").asText()) || !"PUBLISHED".equals(row.path("status").asText())) throw unavailable();
                    Instant start = time(row.path("effectiveAt").asText());
                    Instant end = row.path("expiresAt").isNull() || row.path("expiresAt").isMissingNode() ? null : time(row.path("expiresAt").asText());
                    if (start.isAfter(at) || (end != null && !end.isAfter(at))) continue;
                    long optionId = positiveId(row.path("optionId")), priceId = positiveId(row.path("priceId"));
                    if (!row.path("unitPriceCents").canConvertToLong() || row.path("unitPriceCents").asLong(-1) < 0
                            || !row.path("version").canConvertToInt() || row.path("version").asInt() < 1) throw unavailable();
                    long cents = row.path("unitPriceCents").asLong();
                    String reason = row.path("freeReason").isNull() ? null : row.path("freeReason").asText(null);
                    if (cents == 0 && (reason == null || reason.isBlank())) throw unavailable();
                    var value = new Published(optionId, priceId, cents, row.path("version").asInt(), reason, start, end);
                    if (result.putIfAbsent(optionId, value) != null) throw new ServiceException(409, "AI设计后台存在重复生效基准价，请核对");
                }
                if (seen >= data.path("total").asInt()) return Map.copyOf(result);
                if (rows.isEmpty()) throw unavailable();
            }
            throw unavailable();
        } catch (ServiceException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (Exception e) { throw unavailable(); }
    }
    private static Instant time(String value) { return OffsetDateTime.parse(value).toInstant(); }
    private static long positiveId(JsonNode node) {
        String value = node.asText();
        if (!value.matches("[1-9][0-9]{0,18}")) throw unavailable();
        return Long.parseLong(value);
    }
    private static ServiceException unavailable() { return new ServiceException(503, "AI设计基准价读取失败，请稍后重试"); }
}
