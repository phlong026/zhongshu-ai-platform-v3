package cn.iocoder.yudao.module.franchise.content.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 豆一豆链接解析（D-1.5-3 / 16a A1，逐字节对标 viral_link.py）：
 * GET {base}/api/parse?app_id&url（query 按字典序 urlencode），Sign 头 = MD5(query + secret)。
 * 失败三分法：HTTP≥500/网络→Uncertain；业务 code!=0（过期类关键词）/HTTP 4xx/缺视频→Rejection；
 * 未配置→Unavailable。文案保持中性、不出现供应商名（红线）。
 */
public class DiadiLinkClient implements LinkParsePort {

    private static final Logger log = LoggerFactory.getLogger(DiadiLinkClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s<>'\"]+");
    private static final Pattern BARE_URL_PATTERN = Pattern.compile(
            "\\b(?:[a-zA-Z0-9-]+\\.)*(?:douyin\\.com|xiaohongshu\\.com|xhslink\\.com|iesdouyin\\.com)(?:/[^\\s<>'\"]+)?",
            Pattern.CASE_INSENSITIVE);
    private static final String TRAILING = ".,;:!?，。；：！？、)）]】}";
    private static final Set<String> WECHAT_HOSTS = Set.of(
            "channels.weixin.qq.com", "weixin.qq.com", "www.weixin.qq.com");
    private static final Set<String> DOUYIN_HOSTS = Set.of(
            "douyin.com", "www.douyin.com", "v.douyin.com", "iesdouyin.com", "www.iesdouyin.com");
    private static final Set<String> XHS_HOSTS = Set.of(
            "xiaohongshu.com", "www.xiaohongshu.com", "xhslink.com", "www.xhslink.com");
    private static final List<String> EXPIRED_WORDS = List.of("过期", "失效", "不存在", "下架");
    private static final List<String> DURATION_KEYS = List.of("duration", "video_duration", "time", "length");
    private static final List<String> XHS_ID_KEYS = List.of("note_id", "item_id", "id");
    private static final List<String> DOUYIN_ID_KEYS = List.of("aweme_id", "item_id", "id");

    private final ContentProviderProperties.Diadi config;
    private final ContentProviderTransport transport;
    private final ContentProviderCallRecorder recorder;

    public DiadiLinkClient(ContentProviderProperties.Diadi config, ContentProviderTransport transport,
                           ContentProviderCallRecorder recorder) {
        this.config = config;
        this.transport = transport;
        this.recorder = recorder;
    }

    @Override
    public ParsedLink parse(String shareUrl, ProviderCallAudit audit) {
        if (config.getAppId().isBlank() || config.getAppSecret().isBlank()) {
            throw new ContentProviderException.Unavailable("链接解析服务尚未配置，请联系管理员或上传视频文件");
        }
        String normalized = normalize(shareUrl);
        String platform = platformOf(normalized);

        TreeMap<String, String> params = new TreeMap<>();
        params.put("app_id", config.getAppId());
        params.put("url", normalized);
        String query = formUrlEncode(params);
        String url = config.getBaseUrl() + "/api/parse?" + query;
        String sign = md5Hex(query + config.getAppSecret());

        long startNanos = System.nanoTime();
        ContentProviderTransport.Response response;
        try {
            response = transport.exchange("GET", url, Map.of("Sign", sign), null, config.getTimeoutSeconds());
        } catch (ContentProviderTransport.NetworkFailure e) {
            record(audit, "NETWORK_ERROR", null, startNanos);
            throw new ContentProviderException.Uncertain("链接解析结果未知，请稍后在任务中查看或重新发起");
        } catch (IOException e) {
            record(audit, "NETWORK_ERROR", null, startNanos);
            throw new ContentProviderException.Uncertain("链接解析结果未知，请稍后在任务中查看或重新发起");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            record(audit, "INTERRUPTED", null, startNanos);
            throw new ContentProviderException.Uncertain("链接解析结果未知，请稍后在任务中查看或重新发起");
        }
        if (response.status() >= 500) {
            record(audit, "HTTP_" + response.status(), null, startNanos);
            throw new ContentProviderException.Uncertain("链接解析结果未知，请稍后在任务中查看或重新发起");
        }
        JsonNode root;
        try {
            root = JSON.readTree(response.body());
        } catch (IOException e) {
            record(audit, "PARSE_ERROR", null, startNanos);
            throw new ContentProviderException.Rejection("链接解析返回内容异常，请稍后重试或上传视频文件", response.status(), null);
        }
        if (response.status() < 200 || response.status() >= 300) {
            record(audit, "HTTP_" + response.status(), null, startNanos);
            throw new ContentProviderException.Rejection("链接暂时无法解析，请检查链接或上传视频文件", response.status(), null);
        }
        return interpret(root, platform, audit, startNanos);
    }

    private ParsedLink interpret(JsonNode root, String platform, ProviderCallAudit audit, long startNanos) {
        if (root.path("code").asInt(-1) != 0) {
            String message = root.path("message").asText(root.path("msg").asText(""));
            record(audit, "VENDOR_" + root.path("code").asInt(-1), null, startNanos);
            boolean expired = EXPIRED_WORDS.stream().anyMatch(message::contains);
            if (expired) {
                throw new ContentProviderException.Rejection("视频链接已过期，请重新复制链接或上传视频文件", 200, null);
            }
            throw new ContentProviderException.Rejection("视频链接无法解析，请检查链接或上传视频文件", 200, null);
        }
        JsonNode data = root.path("data");
        String videoUrl = firstUrl(data.path("video"));
        if (videoUrl == null) {
            record(audit, "MEDIA_MISSING", null, startNanos);
            throw new ContentProviderException.Rejection("链接中没有可用视频，请上传视频文件", 200, null);
        }
        String text = data.path("text").asText("").trim();
        String title = data.path("title").asText("").trim();
        if (title.isEmpty()) {
            title = text.length() > 40 ? text.substring(0, 40) : (text.isEmpty() ? "链接视频" : text);
        }
        String cover = data.path("cover").asText(null);
        String author = data.path("author").path("nickname").asText(data.path("author").asText(""));
        String sourceId = firstNonBlank(data, platform.equals("xiaohongshu") ? XHS_ID_KEYS : DOUYIN_ID_KEYS);
        if (sourceId == null) {
            record(audit, "ID_MISSING", null, startNanos);
            throw new ContentProviderException.Rejection("链接未返回有效视频标识，请上传视频文件", 200, null);
        }
        record(audit, "OK", 1, startNanos);
        return new ParsedLink(platform, videoUrl, firstUrl(data.path("audio")), clip(title, 200),
                clip(text, 2000), cover, clip(author, 120), durationMs(data));
    }

    // ---------------- 链接归一（对标 viral_link.normalize_supported_link，仅支持抖音/小红书） ----------------

    private String normalize(String raw) {
        String text = raw == null ? "" : raw.trim();
        Matcher match = URL_PATTERN.matcher(text);
        if (!match.find()) {
            match = BARE_URL_PATTERN.matcher(text);
            if (!match.find()) {
                throw new ContentProviderException.Rejection(
                        "请输入完整的抖音或小红书视频链接，或上传视频文件", 422, null);
            }
        }
        String url = match.group(0).replaceAll("[" + TRAILING + "]+$", "");
        if (!url.toLowerCase(Locale.ROOT).startsWith("http://")
                && !url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            url = "https://" + url;
        }
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            host = null;
        }
        host = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if (WECHAT_HOSTS.contains(host) || host.endsWith(".weixin.qq.com")) {
            throw new ContentProviderException.Rejection("视频号链接暂不支持解析，请上传视频文件", 422, null);
        }
        if (!DOUYIN_HOSTS.contains(host) && !XHS_HOSTS.contains(host) && !host.endsWith(".douyin.com")) {
            throw new ContentProviderException.Rejection("当前支持抖音和小红书视频链接，其他平台请上传视频文件", 422, null);
        }
        return url;
    }

    private String platformOf(String normalizedUrl) {
        String host = URI.create(normalizedUrl).getHost().toLowerCase(Locale.ROOT);
        return XHS_HOSTS.contains(host) ? "xiaohongshu" : "douyin";
    }

    // ---------------- 工具 ----------------

    /** 与 Python urlencode(sorted(params)) 字节一致：URLEncoder 差异仅 * 与 ~，两处替换补齐。 */
    static String formUrlEncode(TreeMap<String, String> params) {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return query.toString();
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    static String md5Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    private static String firstUrl(JsonNode node) {
        if (!node.isArray()) {
            return null;
        }
        for (JsonNode item : node) {
            String candidate = item.path("url").asText(item.path("play_url").asText(null));
            if (candidate == null && item.isTextual()) {
                candidate = item.asText();
            }
            if (candidate != null
                    && (candidate.startsWith("https://") || candidate.startsWith("http://"))) {
                return candidate;
            }
        }
        return null;
    }

    private static String firstNonBlank(JsonNode data, List<String> keys) {
        for (String key : keys) {
            String value = data.path(key).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static long durationMs(JsonNode data) {
        for (String key : DURATION_KEYS) {
            long resolved = coerceDurationMs(data.get(key));
            if (resolved > 0) {
                return resolved;
            }
        }
        return coerceDurationMs(data.path("other").get("duration"));
    }

    private static long coerceDurationMs(JsonNode raw) {
        if (raw == null || raw.isNull() || raw.isBoolean()) {
            return 0;
        }
        double value;
        if (raw.isNumber()) {
            value = raw.asDouble();
        } else if (raw.isTextual() && !raw.asText().isBlank()) {
            try {
                value = Double.parseDouble(raw.asText());
            } catch (NumberFormatException e) {
                return 0;
            }
        } else {
            return 0;
        }
        if (value <= 0) {
            return 0;
        }
        return Math.round(value > 10_000 ? value : value * 1000);
    }

    private static String clip(String value, int max) {
        String clean = value == null ? "" : value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private void record(ProviderCallAudit audit, String resultCode, Integer units, long startNanos) {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        if (recorder != null) {
            recorder.record(audit.companyId(), audit.taskId(), "diadi", "api/parse", elapsedMs, resultCode, units);
        } else {
            log.debug("diadi api/parse result={} units={} latency={}ms (no recorder)", resultCode, units, elapsedMs);
        }
    }

}
