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
    // Jackson's documented UTF-8 options match runtime JSON.stringify, including lone surrogates.
    private static final ObjectMapper SERVICE_JSON = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
            .disable(com.fasterxml.jackson.core.json.JsonWriteFeature.WRITE_HEX_UPPER_CASE)
            .enable(com.fasterxml.jackson.core.json.JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8)
            .build());

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

    public record ServiceReceipt(String status, String polished, String requestHash) { }

    /** Service requests carry a durable key understood by runtime; legacy consumer requests are unchanged. */
    public String polishForService(long accountId, String note, String line, String receiptId) {
        String normalized = serviceNote(note);
        String body;
        try {
            body = new String(SERVICE_JSON.writeValueAsBytes(Map.of("receiptId", receiptId, "note", normalized,
                    "line", "ELEVATION".equals(line) ? "ELEVATION" : "FLAT")), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid note polish request", error);
        }
        Response response = serviceRequest(accountId, PATH, body);
        try {
            JsonNode receipt = mapper.readTree(response.body());
            if (response.status()!=200 || !"SUCCEEDED".equals(receipt.path("status").asText())
                    || !serviceRequestHash(normalized,line).equals(receipt.path("requestHash").asText())) {
                throw new ServiceException(502, "润色结果待收据核实");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            log.warn("[notePolishService][account={} receipt={}] 服务收据解析失败",accountId,receiptId,error);
            throw new ServiceException(502, "润色结果待收据核实");
        }
        return parse(accountId,response,System.nanoTime());
    }

    /** Read-only recovery. An unknown request is never resubmitted by this method. */
    public ServiceReceipt serviceReceipt(long accountId, String receiptId) {
        try {
            String body = mapper.writeValueAsString(Map.of("receiptId", receiptId));
            Response response = serviceRequest(accountId, PATH + "/receipt", body);
            if (response.status() != 200) throw new ServiceException(503, "润色服务收据不可用");
            JsonNode node = mapper.readTree(response.body());
            String status = node.path("status").asText();
            if (!java.util.List.of("NOT_FOUND", "PROCESSING", "SUCCEEDED", "FAILED", "UNKNOWN").contains(status)) {
                throw new ServiceException(503, "润色服务收据无效");
            }
            String polished = node.path("polished").isTextual() ? node.path("polished").asText().trim() : null;
            if ("SUCCEEDED".equals(status) && (polished == null || polished.isBlank())) {
                throw new ServiceException(503, "润色服务收据结果缺失");
            }
            return new ServiceReceipt(status, polished == null ? null : polished.substring(0, Math.min(polished.length(), MAX_OUTPUT)),
                    node.path("requestHash").isTextual() ? node.path("requestHash").asText() : null);
        } catch (ServiceException error) {
            throw error;
        } catch (Exception error) {
            log.warn("[notePolishReceipt][account={} receipt={}] 收据读取失败", accountId, receiptId, error);
            throw new ServiceException(503, "润色服务收据不可用");
        }
    }

    /** Runtime canonical hash: JSON.stringify({note,line}), both strings, in this exact order. */
    public static String serviceRequestHash(String note, String line) {
        try {
            var canonical = new LinkedHashMap<String,String>();
            canonical.put("note",serviceNote(note));
            canonical.put("line","ELEVATION".equals(line)?"ELEVATION":"FLAT");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    SERVICE_JSON.writeValueAsBytes(canonical)));
        } catch (java.security.NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Cannot hash runtime receipt request",error);
        }
    }

    private static String serviceNote(String note) {
        if (note==null) throw new ServiceException(400,"请先输入补充需求");
        int start=0,end=note.length();
        while(start<end && jsWhitespace(note.charAt(start))) start++;
        while(end>start && jsWhitespace(note.charAt(end-1))) end--;
        String normalized=note.substring(start,end);
        if (normalized.isEmpty() || normalized.length()>MAX_NOTE) throw new ServiceException(400,"补充需求需为1至200字");
        return normalized;
    }

    private static boolean jsWhitespace(char value) {
        return value==0xFEFF || value==9 || value==11 || value==12 || value==10 || value==13
                || value==0x2028 || value==0x2029 || Character.getType(value)==Character.SPACE_SEPARATOR;
    }

    private Response serviceRequest(long accountId, String path, String body) {
        String origin = runtimeOrigin();
        if (origin == null || blank(internalSecret)) throw new ServiceException(503, NOT_CONFIGURED);
        boolean submission = PATH.equals(path);
        // Receipt polling does not call a model and must not exhaust the model's daily allowance.
        if (submission) limiter.acquire(accountId);
        try {
            return transport.post(origin + path, body, signedHeaders(body, path));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            log.warn("[notePolishService][account={} path={}] 调用中断", accountId, path, error);
            throw new ServiceException(502, FAILED);
        } catch (Exception error) {
            log.warn("[notePolishService][account={} path={}] 调用失败", accountId, path, error);
            throw new ServiceException(502, FAILED);
        } finally {
            if (submission) limiter.release();
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
        return signedHeaders(body, PATH);
    }

    private Map<String, String> signedHeaders(String body, String path) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-ZS-Timestamp", timestamp);
        headers.put("X-ZS-Signature", signature(internalSecret, timestamp, body, path));
        return headers;
    }

    /** 签名口径单点定义；跨语言黄金向量测试（NotePolishServiceTest / note-polish.test.mjs）锁住它 */
    public static String signature(String secret, String timestamp, String body) throws Exception {
        return signature(secret, timestamp, body, PATH);
    }

    private static String signature(String secret, String timestamp, String body, String path) throws Exception {
        String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(
                (timestamp + "\nPOST\n" + path + "\n" + bodyHash).getBytes(StandardCharsets.UTF_8)));
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
