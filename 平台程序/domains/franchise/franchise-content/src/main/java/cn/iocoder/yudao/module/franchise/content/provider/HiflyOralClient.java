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
            "st_pos_x", "st_pos_y", "st_primary_color", "st_outline_color");

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
        validateInput(text, request.subtitle());
        ObjectNode payload = JSON.createObjectNode();
        payload.put("voice", request.voice());
        payload.put("text", text);
        payload.put("avatar", request.avatar());
        payload.put("title", clip(request.title(), MAX_TITLE_CHARS));
        payload.put("aigc_flag", 1);
        if (request.subtitle() != null) {
            for (Map.Entry<String, Object> entry : normalizeSubtitle(request.subtitle()).entrySet()) {
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
        if(response.status()<200||response.status()>=300)throw new ContentProviderException.Unavailable("制作状态查询暂不可用，请稍后重试");
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
        JsonNode nested = envelope.get("data");
        JsonNode data = nested != null && nested.isObject() ? nested : envelope;
        // vendor 拼写怪癖：video_Url；兼容常规 video_url（hifly.py:613-614）
        String videoUrl = data.path("video_Url").asText(data.path("video_url").asText(null));
        JsonNode duration = data.get("duration");
        record(audit, "video/task", "OK", null, latency);
        String status = vendorStatus(data.path("status"));
        if ("DONE".equals(status) && !usableVideoUrl(videoUrl)) status = "UNKNOWN";
        return new Snapshot(status,
                videoUrl, duration != null && duration.isNumber() ? duration.asInt() : null);
    }

    private static boolean usableVideoUrl(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            var uri = java.net.URI.create(value);
            return uri.getHost() != null && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException e) { return false; }
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
        if(response.status()<200||response.status()>=300)throw new ContentProviderException.Uncertain("制作提交结果待核实，请勿重复提交");
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
        return HiflyErrors.message(code);
    }

    public static void validateInput(String text,Map<String,Object> subtitle) {
        if(text==null||text.isBlank()||text.length()>MAX_TEXT_CHARS)throw new ContentProviderException.Rejection("口播文案须为1至10000字",422,null);
        if(java.util.regex.Pattern.compile("<\\s*/?\\s*[A-Za-z][^>]*>").matcher(text).find())throw new ContentProviderException.Rejection("口播文案不支持HTML标签，请使用纯文字",422,null);
        normalizeSubtitle(subtitle);
    }
    private static Map<String,Object> normalizeSubtitle(Map<String,Object> source) {
        Map<String,Object> normalized=new LinkedHashMap<>();if(source==null)return normalized;
        source.forEach((key,value)->{
            String field="st_x".equals(key)?"st_pos_x":"st_y".equals(key)?"st_pos_y":key;
            if(!SUBTITLE_KEYS.contains(field)||value==null)throw new ContentProviderException.Rejection("字幕参数不支持："+key,422,null);
            if(normalized.containsKey(field)&&!String.valueOf(normalized.get(field)).equals(String.valueOf(value)))throw new ContentProviderException.Rejection("字幕坐标参数冲突",422,null);
            Object checked=value;
            if(field.equals("st_font_name")){if(!(value instanceof String)||String.valueOf(value).isBlank())throw new ContentProviderException.Rejection("字幕字体名称无效",422,null);}
            else if(field.endsWith("color")){if(!(value instanceof String)||!String.valueOf(value).matches("#[0-9a-fA-F]{6}"))throw new ContentProviderException.Rejection("字幕颜色须为#RRGGBB",422,null);}
            else {
                String raw=value instanceof Boolean flag?(flag?"1":"0"):String.valueOf(value);
                long number;try{number=Long.parseLong(raw);}catch(NumberFormatException error){throw new ContentProviderException.Rejection("字幕数值参数须为整数",422,null);}
                if(number<0||number>Integer.MAX_VALUE||field.equals("st_show")&&number>1||!field.startsWith("st_pos_")&&!field.equals("st_show")&&number==0)throw new ContentProviderException.Rejection("字幕数值参数超出范围",422,null);
                checked=(int)number;
            }
            normalized.put(field,checked);
        });return normalized;
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
