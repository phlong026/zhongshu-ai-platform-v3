package cn.iocoder.yudao.module.franchise.content.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * HiFly 数字人口播 V2（D-1.5-5 / 16a A1，对标 hifly.py）：
 * POST {base}/api/v2/hifly/video/create_by_tts（Bearer；voice/text≤10000/avatar/title≤20/aigc_flag=1；
 * 字幕参数平铺进 payload）→ 轮询 GET /video/task?task_id=（状态 1 等待/2 处理/3 成/4 败）。
 * 怪癖：成片字段是 video_Url（大写 U）。vendor code!=0→Rejection（含 1002 余额不足等已知码映射）；
 * 非 vendor 失败的提交期网络错误→Uncertain（任务可能已创建）；未知状态→UNKNOWN 按在途继续轮询。
 */
public class HiflyOralClient implements OralVideoPort {

    private static final Logger log = LoggerFactory.getLogger(HiflyOralClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TITLE_CHARS = 20;
    private static final int MAX_TEXT_CHARS = 10_000;
    private static final Set<String> SUBTITLE_KEYS = Set.of(
            "st_show", "st_font_name", "st_font_size", "st_width", "st_height",
            "st_x", "st_y", "st_primary_color", "st_outline_color");

    /** 常见 vendor 业务码→中性文案（对标 hifly._VENDOR_CODE_MESSAGES，不出现供应商名） */
    private static final Map<Integer, String> VENDOR_MESSAGES = Map.ofEntries(
            Map.entry(11, "数字人接口参数校验未通过，请检查提交内容"),
            Map.entry(14, "数字人接口未找到对应资源"),
            Map.entry(1001, "数字人任务并发已达上限，请稍后重试"),
            Map.entry(1002, "数字人服务余额不足，请联系管理员"),
            Map.entry(1005, "当前服务套餐不支持该能力"),
            Map.entry(1006, "当前服务套餐权限不足"),
            Map.entry(1009, "声音克隆数量已达套餐上限"),
            Map.entry(1011, "该素材未通过数字人服务合规校验"),
            Map.entry(1013, "声音克隆被限制，请稍后重试"),
            Map.entry(1015, "数字人任务提交数已达上限，请稍后重试"),
            Map.entry(2003, "数字人服务未正确配置，请联系管理员"));

    private final ContentProviderProperties.Hifly config;
    private final ContentProviderTransport transport;
    private final ContentProviderCallRecorder recorder;

    public HiflyOralClient(ContentProviderProperties.Hifly config, ContentProviderTransport transport,
                           ContentProviderCallRecorder recorder) {
        this.config = config;
        this.transport = transport;
        this.recorder = recorder;
    }

    @Override
    public String createByTts(TtsRequest request) {
        if (config.getApiKey().isBlank()) {
            throw new ContentProviderException.Unavailable("数字人口播服务尚未配置，请联系管理员");
        }
        String text = request.text() == null ? "" : request.text().trim();
        if (text.isEmpty()) {
            throw new ContentProviderException.Rejection("口播文案不能为空", 422, null);
        }
        if (text.length() > MAX_TEXT_CHARS) {
            throw new ContentProviderException.Rejection("口播文案超出 10000 字上限，请精简后提交", 422, null);
        }
        ObjectNode payload = JSON.createObjectNode();
        payload.put("voice", request.voice());
        payload.put("text", text);
        payload.put("avatar", request.avatar());
        payload.put("title", clip(request.title(), MAX_TITLE_CHARS));
        payload.put("aigc_flag", 1);
        if (request.subtitle() != null) {
            for (Map.Entry<String, Object> entry : request.subtitle().entrySet()) {
                if (!SUBTITLE_KEYS.contains(entry.getKey())) {
                    throw new ContentProviderException.Rejection("字幕参数包含不支持的字段：" + entry.getKey(), 422, null);
                }
                if (entry.getValue() instanceof Integer number) {
                    payload.put(entry.getKey(), number.intValue());
                } else if (entry.getValue() instanceof Long number) {
                    payload.put(entry.getKey(), number.longValue());
                } else if (entry.getValue() instanceof Double number) {
                    payload.put(entry.getKey(), number.doubleValue());
                } else if (entry.getValue() instanceof Boolean flag) {
                    payload.put(entry.getKey(), flag ? 1 : 0);
                } else {
                    payload.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
        }

        String endpoint = "video/create_by_tts";
        long start = System.nanoTime();
        JsonNode data;
        try {
            data = postForEnvelope(config.getBaseUrl() + "/api/v2/hifly/video/create_by_tts",
                    payload, request.audit(), endpoint, start);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ContentProviderException.Uncertain("数字人口播提交结果未知，请稍后在任务中查看，切勿重复提交");
        }
        String taskId = data.path("task_id").asText("");
        if (taskId.isBlank()) {
            throw new ContentProviderException.Uncertain("数字人口播服务未返回任务号，请稍后在任务中查看");
        }
        if (request.onCreated() != null) {
            request.onCreated().accept(taskId); // 提交即持久化（A2.1）
        }
        return taskId;
    }

    @Override
    public Snapshot poll(String providerTaskId, ProviderCallAudit audit) {
        String url = config.getBaseUrl() + "/api/v2/hifly/video/task?task_id=" + encode(providerTaskId);
        long start = System.nanoTime();
        ContentProviderTransport.Response response;
        try {
            response = transport.exchange("GET", url,
                    Map.of("Authorization", "Bearer " + config.getApiKey(), "Accept", "application/json"),
                    null, config.getTimeoutSeconds());
        } catch (ContentProviderTransport.NetworkFailure e) {
            record(audit, "video/task", e.isTimeout() ? "TIMEOUT" : "NETWORK_ERROR", null, elapsed(start));
            throw new ContentProviderException.Unavailable("数字人口播服务连接暂时中断，请稍后重试");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            record(audit, "video/task", "NETWORK_ERROR", null, elapsed(start));
            throw new ContentProviderException.Unavailable("数字人口播服务连接暂时中断，请稍后重试");
        }
        long latency = elapsed(start);
        if (response.status() == 401 || response.status() == 403) {
            record(audit, "video/task", "HTTP_" + response.status(), null, latency);
            throw new ContentProviderException.Unavailable("数字人口播服务凭据无效或无权限，请联系管理员");
        }
        JsonNode envelope;
        try {
            envelope = JSON.readTree(response.body());
        } catch (IOException e) {
            record(audit, "video/task", "PARSE_ERROR", null, latency);
            throw new ContentProviderException.Unavailable("数字人口播服务响应异常，请稍后重试");
        }
        if (envelope.path("code").asInt(0) != 0) {
            int code = envelope.path("code").asInt();
            record(audit, "video/task", "VENDOR_" + code, null, latency);
            throw new ContentProviderException.Rejection(vendorMessage(code, envelope), response.status(),
                    String.valueOf(code));
        }
        JsonNode data = envelope.path("data");
        // vendor 拼写怪癖：video_Url；兼容常规 video_url（hifly.py:613-614）
        String videoUrl = data.path("video_Url").asText(data.path("video_url").asText(null));
        JsonNode duration = data.get("duration");
        record(audit, "video/task", "OK", null, latency);
        return new Snapshot(vendorStatus(data.path("status")),
                videoUrl, duration != null && duration.isNumber() ? duration.asInt() : null);
    }

    // ---------------- 公共 ----------------

    private JsonNode postForEnvelope(String url, ObjectNode payload, ProviderCallAudit audit,
                                     String endpoint, long start) throws IOException, InterruptedException {
        ContentProviderTransport.Response response;
        try {
            response = transport.exchange("POST", url,
                    Map.of("Authorization", "Bearer " + config.getApiKey(),
                            "Content-Type", "application/json", "Accept", "application/json"),
                    payload.toString().getBytes(StandardCharsets.UTF_8), config.getTimeoutSeconds());
        } catch (ContentProviderTransport.NetworkFailure e) {
            record(audit, endpoint, e.isTimeout() ? "TIMEOUT" : "NETWORK_ERROR", null, elapsed(start));
            throw e;
        }
        long latency = elapsed(start);
        if (response.status() == 401 || response.status() == 403) {
            record(audit, endpoint, "HTTP_" + response.status(), null, latency);
            throw new ContentProviderException.Unavailable("数字人口播服务凭据无效或无权限，请联系管理员");
        }
        JsonNode envelope;
        try {
            envelope = JSON.readTree(response.body());
        } catch (IOException e) {
            // HTTP 层成功但响应不可判读：提交可能已受理——Uncertain
            record(audit, endpoint, "PARSE_ERROR", null, latency);
            throw new ContentProviderException.Uncertain("数字人口播提交结果未知，请稍后在任务中查看，切勿重复提交");
        }
        if (envelope.path("code").asInt(-1) != 0) {
            int code = envelope.path("code").asInt(-1);
            record(audit, endpoint, "VENDOR_" + code, null, latency);
            throw new ContentProviderException.Rejection(vendorMessage(code, envelope), response.status(),
                    String.valueOf(code));
        }
        record(audit, endpoint, "SUBMITTED", null, latency);
        JsonNode data = envelope.get("data");
        return data != null && data.isObject() ? data : envelope;
    }

    private static String vendorStatus(JsonNode status) {
        if (status == null || !status.isNumber()) {
            return "UNKNOWN";
        }
        return switch (status.asInt()) {
            case 1 -> "WAITING";
            case 2 -> "PROCESSING";
            case 3 -> "DONE";
            case 4 -> "FAILED";
            default -> "UNKNOWN";
        };
    }

    private static String vendorMessage(int code, JsonNode envelope) {
        String known = VENDOR_MESSAGES.get(code);
        if (known != null) {
            return known;
        }
        String raw = envelope.path("message").asText(envelope.path("msg").asText(""));
        String composed = "数字人服务返回错误 " + code + "：" + raw;
        return composed.length() <= 200 ? composed : composed.substring(0, 200);
    }

    private static String clip(String value, int max) {
        String clean = value == null ? "" : value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private void record(ProviderCallAudit audit, String endpoint, String resultCode, Integer units, long latencyMs) {
        if (recorder != null) {
            recorder.record(audit.companyId(), audit.taskId(), "hifly", endpoint, latencyMs, resultCode, units);
        } else {
            log.debug("hifly {} result={} units={} latency={}ms (no recorder)", endpoint, resultCode, units, latencyMs);
        }
    }

}
