package cn.iocoder.yudao.module.design.ai;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 补充需求「AI 优化」：把用户随手写的建房需求润色为条理清晰的设计描述（同步文本轻调用）。
 *
 * <p>架构边界：供应商密钥只在 ai-runtime 进程里。本服务以内部签名调用 runtime 的
 * {@code POST /internal/note-polish}（HMAC-SHA256，与 runtime 领任务时同一套、同一把共享密钥
 * {@code zhongshu.ai.internal-secret}），后端进程不持有、不接触供应商密钥。
 * 未配置 runtime 地址或共享密钥时显式降级为"暂未配置"，不产生任何出站调用。
 */
@Slf4j
@Service
public class NotePolishService {

    /** 出站响应（状态码 + 原始响应体）；测试注入桩 */
    public record Response(int status, String body) {
    }

    /** 出站传输抽象：生产用 JDK HttpClient，测试注入桩 */
    public interface Transport {
        Response post(String url, String body, Map<String, String> headers) throws Exception;
    }

    static final String PATH = "/internal/note-polish";
    private static final int MAX_NOTE = 200;
    private static final int MAX_OUTPUT = 200;
    private static final String NOT_CONFIGURED = "AI 优化暂未配置，请联系管理员";
    private static final String FAILED = "AI 优化失败，请稍后再试";
    private static final String BUSY = "AI 优化繁忙，请稍后再试";

    /** 单例：HttpClient 每个实例自带线程，不应每次调用新建 */
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final NotePolishProperties properties;
    private final String internalSecret;
    private final Transport transport;
    private final NotePolishLimiter limiter;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public NotePolishService(NotePolishProperties properties,
                             @Value("${zhongshu.ai.internal-secret:}") String internalSecret) {
        this(properties, internalSecret, NotePolishService::defaultTransport, new NotePolishLimiter());
    }

    public NotePolishService(NotePolishProperties properties, String internalSecret, Transport transport,
                             NotePolishLimiter limiter) {
        this.properties = properties;
        this.internalSecret = internalSecret;
        this.transport = transport;
        this.limiter = limiter;
    }

    /** @param line FLAT=平面布置语境 ELEVATION=外观风格语境 */
    public String polish(long accountId, String note, String line) {
        if (note == null || note.isBlank()) throw new ServiceException(400, "请先输入补充需求");
        if (note.length() > MAX_NOTE) note = note.substring(0, MAX_NOTE);
        String origin = runtimeOrigin();
        if (origin == null || blank(internalSecret)) throw new ServiceException(503, NOT_CONFIGURED);

        limiter.acquire(accountId);
        long started = System.nanoTime();
        try {
            String body = mapper.writeValueAsString(Map.of("note", note, "line", "ELEVATION".equals(line) ? "ELEVATION" : "FLAT"));
            Response response = transport.post(origin + PATH, body, signedHeaders(body));
            return parse(accountId, response, started);
        } catch (ServiceException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[notePolish][account={} 调用被中断]", accountId);
            throw new ServiceException(502, FAILED);
        } catch (Exception e) {
            // 只记类型与耗时，不记 note 内容（用户输入）与任何密钥
            log.warn("[notePolish][account={} 调用 runtime 失败 type={} cost={}ms]", accountId,
                    e.getClass().getSimpleName(), elapsedMs(started), e);
            throw new ServiceException(502, FAILED);
        } finally {
            limiter.release();
        }
    }

    private String parse(long accountId, Response response, long started) {
        if (response.status() == 429) {
            log.warn("[notePolish][account={} runtime 限流 status=429 cost={}ms]", accountId, elapsedMs(started));
            throw new ServiceException(429, BUSY);
        }
        String polished = null;
        if (response.status() == 200) {
            try {
                JsonNode node = mapper.readTree(response.body() == null ? "" : response.body()).path("polished");
                polished = node.isTextual() ? node.asText().trim() : null;
            } catch (Exception e) {
                polished = null;
            }
        }
        if (polished == null || polished.isEmpty()) {
            log.warn("[notePolish][account={} runtime 返回不可用 status={} cost={}ms]", accountId, response.status(), elapsedMs(started));
            throw new ServiceException(502, FAILED);
        }
        return polished.length() > MAX_OUTPUT ? polished.substring(0, MAX_OUTPUT) : polished;
    }

    /** runtime 地址必须是不含路径/凭据/查询串的 http(s) origin；否则视为未配置，避免签名路径与实际路径不一致 */
    private String runtimeOrigin() {
        String raw = properties == null ? null : properties.getRuntimeUrl();
        if (blank(raw)) return null;
        try {
            URI uri = URI.create(raw.trim());
            boolean schemeOk = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
            boolean pathOk = uri.getRawPath() == null || uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath());
            if (!schemeOk || uri.getHost() == null || uri.getUserInfo() != null || !pathOk
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) return null;
            return uri.getScheme() + "://" + uri.getRawAuthority();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** HMAC-SHA256(secret, "{timestamp}\nPOST\n{path}\n{sha256(body)}")，与 InternalSignatureVerifier / ai-runtime 一致 */
    private Map<String, String> signedHeaders(String body) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-ZS-Timestamp", timestamp);
        headers.put("X-ZS-Signature", signature(internalSecret, timestamp, body));
        return headers;
    }

    /** 签名口径单点定义；跨语言黄金向量测试（NotePolishServiceTest / note-polish.test.mjs）锁住它 */
    public static String signature(String secret, String timestamp, String body) throws Exception {
        String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(
                (timestamp + "\nPOST\n" + PATH + "\n" + bodyHash).getBytes(StandardCharsets.UTF_8)));
    }

    private static Response defaultTransport(String url, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
