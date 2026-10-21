package cn.iocoder.yudao.module.platform.identity.wechat;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.platform.identity.PlatformWechatProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.*;

/**
 * 直连微信的员工登录客户端。安全约束沿用旧 identity 模块 RealWechatIdentityAdapter 的做法：
 * <ul>
 *   <li>secret 只在服务端配置；不跟随重定向（避免带凭据的 URL 被转发到其他主机）；</li>
 *   <li>一次性 code 不重试，并关闭 JDK HttpClient 的连接自动重试；</li>
 *   <li>建连 + 读取有总时限，防止请求线程被慢响应挂起；</li>
 *   <li>日志与异常只含 errcode 与本地描述，不带 cause、响应体、URL、secret、session_key、access_token、手机号。</li>
 * </ul>
 * access_token 用微信稳定版接口获取，按 AppID 缓存到过期前 5 分钟；收到凭据失效类错误码时丢弃缓存。
 */
@Component
@ConditionalOnProperty(prefix = "platform.identity.wechat", name = "provider", havingValue = "real", matchIfMissing = true)
@Slf4j
public class RealWechatStaffClient implements WechatStaffClient {

    static {
        if (System.getProperty("jdk.httpclient.disableRetryConnect") == null) {
            System.setProperty("jdk.httpclient.disableRetryConnect", "true");
        }
    }

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    /** code 无效、已使用、过期 */
    private static final int ERR_CODE_INVALID = 40029;
    private static final int ERR_CODE_USED = 40163;
    /** access_token 无效或过期 */
    private static final int ERR_TOKEN_INVALID = 40001;
    private static final int ERR_TOKEN_EXPIRED = 42001;
    private static final long TOKEN_EARLY_EXPIRE_SECONDS = 300;

    private final PlatformWechatProperties props;
    private final String baseUrl;
    private final HttpClient http;
    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();

    public RealWechatStaffClient(PlatformWechatProperties props) {
        this.props = props;
        URI base = URI.create(props.getBaseUrl());
        if (base.getHost() == null || !("https".equalsIgnoreCase(base.getScheme()) || "http".equalsIgnoreCase(base.getScheme()))
                || base.getQuery() != null) {
            throw new IllegalArgumentException("platform.identity.wechat.base-url 非法");
        }
        this.baseUrl = props.getBaseUrl().replaceAll("/+$", "");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String openid(String appid, String loginCode) {
        String secret = requireSecret(appid);
        JsonNode body = send(HttpRequest.newBuilder(URI.create(baseUrl + "/sns/jscode2session?appid=" + enc(appid)
                + "&secret=" + enc(secret) + "&js_code=" + enc(loginCode) + "&grant_type=authorization_code")).GET(), "code2session");
        int errcode = body.path("errcode").asInt(0);
        if (errcode != 0) {
            throw fail("code2session", errcode, isCodeError(errcode) ? WECHAT_CODE_INVALID : WECHAT_SERVICE_UNAVAILABLE);
        }
        String openid = body.path("openid").asText("");
        if (openid.isBlank()) {
            throw fail("code2session", 0, WECHAT_SERVICE_UNAVAILABLE);
        }
        return openid;
    }

    @Override
    public String phoneNumber(String appid, String phoneCode) {
        String token = accessToken(appid);
        String json = "{\"code\":" + quote(phoneCode) + "}";
        JsonNode body = send(HttpRequest.newBuilder(URI.create(baseUrl + "/wxa/business/getuserphonenumber?access_token=" + enc(token)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)), "getuserphonenumber");
        int errcode = body.path("errcode").asInt(0);
        if (errcode == ERR_TOKEN_INVALID || errcode == ERR_TOKEN_EXPIRED) {
            tokens.remove(appid);
        }
        if (errcode != 0) {
            throw fail("getuserphonenumber", errcode, isCodeError(errcode) ? WECHAT_CODE_INVALID : WECHAT_SERVICE_UNAVAILABLE);
        }
        JsonNode info = body.path("phone_info");
        String phone = info.path("purePhoneNumber").asText("");
        if (!"86".equals(info.path("countryCode").asText("")) || !phone.matches("1\\d{10}")) {
            log.warn("[getuserphonenumber][appid={}] 非中国大陆手机号或格式异常，按未开通处理", appid);
            throw new ServiceException(STAFF_NOT_ENROLLED);
        }
        return phone;
    }

    private String accessToken(String appid) {
        CachedToken cached = tokens.get(appid);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.token();
        }
        String secret = requireSecret(appid);
        String json = "{\"grant_type\":\"client_credential\",\"appid\":" + quote(appid) + ",\"secret\":" + quote(secret) + "}";
        JsonNode body = send(HttpRequest.newBuilder(URI.create(baseUrl + "/cgi-bin/stable_token"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)), "stable_token");
        String token = body.path("access_token").asText("");
        long expiresIn = body.path("expires_in").asLong(0);
        if (token.isBlank() || expiresIn <= 0) {
            throw fail("stable_token", body.path("errcode").asInt(0), WECHAT_SERVICE_UNAVAILABLE);
        }
        tokens.put(appid, new CachedToken(token, Instant.now().plusSeconds(Math.max(0, expiresIn - TOKEN_EARLY_EXPIRE_SECONDS))));
        return token;
    }

    private JsonNode send(HttpRequest.Builder builder, String api) {
        HttpRequest request = builder.timeout(Duration.ofMillis(props.getReadTimeoutMs())).build();
        CompletableFuture<HttpResponse<String>> future = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> response;
        try {
            response = future.get(props.getConnectTimeoutMs() + props.getReadTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw fail(api, 0, WECHAT_SERVICE_UNAVAILABLE);
        } catch (Exception e) {
            future.cancel(true);
            log.warn("[{}] 调用微信失败：{}", api, e.getClass().getSimpleName()); // 只记异常类型，消息可能含带凭据的 URL
            throw new ServiceException(WECHAT_SERVICE_UNAVAILABLE);
        }
        if (response.statusCode() != 200) {
            throw fail(api, 0, WECHAT_SERVICE_UNAVAILABLE);
        }
        try {
            return MAPPER.readTree(response.body());
        } catch (Exception e) {
            log.warn("[{}] 微信响应无法解析：{}", api, e.getClass().getSimpleName());
            throw new ServiceException(WECHAT_SERVICE_UNAVAILABLE);
        }
    }

    private String requireSecret(String appid) {
        return props.secretOf(appid).orElseThrow(() -> new ServiceException(WECHAT_APP_NOT_ALLOWED));
    }

    private static boolean isCodeError(int errcode) {
        return errcode == ERR_CODE_INVALID || errcode == ERR_CODE_USED;
    }

    private static ServiceException fail(String api, int errcode, ErrorCode code) {
        log.warn("[{}] 微信返回失败 errcode={}", api, errcode);
        return new ServiceException(code);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String quote(String value) {
        try {
            return MAPPER.writeValueAsString(value == null ? "" : value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 字符串转义失败");
        }
    }

    private record CachedToken(String token, Instant expiresAt) {
    }

}
