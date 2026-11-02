package cn.iocoder.yudao.module.franchise.content.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * MiniMax-H3 经秘塔网关（D-1.5-6 / 16a A1+A2，对标 generation.py）：
 * 提交 POST {base}/api/minimax/v2/video_generation → 查询 GET .../query/video_generation/{id}。
 * 必搬件全量落地：提交即持久化（onCreated）；明确拒绝白名单 {400,401,402,403,404,422} 直接终态
 * （402 误判曾致整体停摆——2026-10-13 教训，generation.py:598-603）；其余网络类→SUBMISSION_UNCERTAIN
 * 绝不自动重投；含首尾帧 ratio 归一 adaptive；媒体须 HTTPS；直链 HEAD 过期探测 403/404/410。
 */
public class MetasoH3Client implements VideoGenerationPort {

    private static final Logger log = LoggerFactory.getLogger(MetasoH3Client.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MODEL = "MiniMax-H3";
    private static final Set<Integer> REJECTION_WHITELIST = Set.of(400, 401, 402, 403, 404, 422);
    private static final Set<String> RESOLUTIONS = Set.of("768P", "2K");
    private static final Set<String> RATIOS = Set.of("adaptive", "21:9", "16:9", "4:3", "1:1", "3:4", "9:16");
    private static final Set<String> FRAME_ROLES = Set.of("first_frame", "last_frame");
    private static final Set<Integer> EXPIRED_STATUSES = Set.of(403, 404, 410);
    private static final Set<Integer> HEAD_UNSUPPORTED = Set.of(405, 501);
    private static final int PROBE_TIMEOUT_SECONDS = 10;
    private static final Map<String, String> REFERENCE_TYPES = Map.of(
            "reference_image", "image_url", "reference_video", "video_url", "reference_audio", "audio_url");

    private final ContentProviderProperties.Metaso config;
    private final ContentProviderTransport transport;
    private final ContentProviderCallRecorder recorder;

    public MetasoH3Client(ContentProviderProperties.Metaso config, ContentProviderTransport transport,
                          ContentProviderCallRecorder recorder) {
        this.config = config;
        this.transport = transport;
        this.recorder = recorder;
    }

    @Override
    public String submit(H3Request request, Consumer<String> onCreated, ProviderCallAudit audit) {
        if (config.getApiKey().isBlank()) {
            throw new ContentProviderException.Unavailable("视频生成服务尚未配置，请联系管理员");
        }
        validate(request);
        ObjectNode payload = buildRequest(request);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + config.getApiKey());
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");

        String endpoint = "video_generation";
        long start = System.nanoTime();
        ContentProviderTransport.Response response;
        try {
            response = transport.exchange("POST", config.getBaseUrl() + "/api/minimax/v2/video_generation",
                    headers, payload.toString().getBytes(StandardCharsets.UTF_8), config.getTimeoutSeconds());
        } catch (ContentProviderTransport.NetworkFailure e) {
            record(audit, endpoint, e.isTimeout() ? "TIMEOUT" : "NETWORK_ERROR", null, elapsed(start));
            throw new ContentProviderException.Uncertain("视频生成提交结果未知，请稍后在任务中查看，切勿重复提交");
        } catch (IOException e) {
            record(audit, endpoint, "NETWORK_ERROR", null, elapsed(start));
            throw new ContentProviderException.Uncertain("视频生成提交结果未知，请稍后在任务中查看，切勿重复提交");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            record(audit, endpoint, "INTERRUPTED", null, elapsed(start));
            throw new ContentProviderException.Uncertain("视频生成提交结果未知，请稍后在任务中查看，切勿重复提交");
        }
        long latency = elapsed(start);
        if (response.status() == 401 || response.status() == 403) {
            record(audit, endpoint, "HTTP_" + response.status(), null, latency);
            throw new ContentProviderException.Unavailable("视频生成服务凭据无效或无权限，请联系管理员");
        }
        if (REJECTION_WHITELIST.contains(response.status())) {
            // 明确拒绝：请求未被受理、没有任务、没有扣费——直接终态，绝不占用 UNCERTAIN（白名单教训）
            record(audit, endpoint, "HTTP_" + response.status(), null, latency);
            throw new ContentProviderException.Rejection(
                    "视频生成服务暂时不可用，本次未扣费，请稍后重试", response.status(), null);
        }
        if (response.status() < 200 || response.status() >= 300) {
            record(audit, endpoint, "HTTP_" + response.status(), null, latency);
            throw new ContentProviderException.Uncertain("视频生成提交结果未知，请稍后在任务中查看，切勿重复提交");
        }
        String taskId;
        try {
            taskId = JSON.readTree(response.body()).path("task_id").asText("");
        } catch (IOException e) {
            taskId = "";
        }
        if (taskId.isBlank()) {
            record(audit, endpoint, "TASK_ID_MISSING", null, latency);
            throw new ContentProviderException.Uncertain("视频生成提交结果未知，请稍后在任务中查看，切勿重复提交");
        }
        record(audit, endpoint, "SUBMITTED", null, latency);
        if (onCreated != null) {
            onCreated.accept(taskId); // 提交即持久化（A2.1）
        }
        return taskId;
    }

    @Override
    public H3Snapshot poll(String providerTaskId, ProviderCallAudit audit) {
        String endpoint = "query/video_generation";
        String url = config.getBaseUrl() + "/api/minimax/v2/query/video_generation/" + encode(providerTaskId);
        long start = System.nanoTime();
        ContentProviderTransport.Response response;
        try {
            response = transport.exchange("GET", url,
                    Map.of("Authorization", "Bearer " + config.getApiKey(), "Accept", "application/json"),
                    null, config.getTimeoutSeconds());
        } catch (ContentProviderTransport.NetworkFailure e) {
            record(audit, endpoint, e.isTimeout() ? "TIMEOUT" : "NETWORK_ERROR", null, elapsed(start));
            throw new ContentProviderException.Unavailable("视频生成服务连接暂时中断，请稍后重试");
        } catch (IOException e) {
            record(audit, endpoint, "NETWORK_ERROR", null, elapsed(start));
            throw new ContentProviderException.Unavailable("视频生成服务连接暂时中断，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            record(audit, endpoint, "INTERRUPTED", null, elapsed(start));
            throw new ContentProviderException.Unavailable("视频生成服务连接暂时中断，请稍后重试");
        }
        long latency = elapsed(start);
        if (response.status() == 401 || response.status() == 403) {
            record(audit, endpoint, "HTTP_" + response.status(), null, latency);
            throw new ContentProviderException.Unavailable("视频生成服务凭据无效或无权限，请联系管理员");
        }
        if (response.status() == 404) {
            record(audit, endpoint, "HTTP_404", null, latency);
            throw new ContentProviderException.Unavailable("视频生成服务查询暂时不可用，请稍后重试");
        }
        JsonNode root;
        try {
            root = JSON.readTree(response.body());
        } catch (IOException e) {
            record(audit, endpoint, "PARSE_ERROR", null, latency);
            throw new ContentProviderException.Unavailable("视频生成服务响应异常，请稍后重试");
        }
        JsonNode task = extractTask(root, providerTaskId);
        String status = task.path("status").asText("");
        switch (status) {
            case "succeeded" -> {
                String resultUrl = task.path("content").path("url").asText(null);
                if (resultUrl == null || !resultUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
                    record(audit, endpoint, "RESULT_URL_INVALID", null, latency);
                    throw new ContentProviderException.Rejection(
                            "视频生成服务未返回有效的 HTTPS 成片地址，请联系管理员核查任务", 200, null);
                }
                Double outputSeconds = task.path("usage").path("output_seconds").isNumber()
                        ? task.path("usage").path("output_seconds").asDouble() : null;
                record(audit, endpoint, "SUCCEEDED",
                        outputSeconds != null ? (int) Math.ceil(outputSeconds) : null, latency);
                return new H3Snapshot("SUCCEEDED", resultUrl, outputSeconds, null);
            }
            case "failed" -> {
                record(audit, endpoint, "FAILED", null, latency);
                return new H3Snapshot("FAILED", null, null, failureReason(task));
            }
            case "cancelled" -> {
                record(audit, endpoint, "CANCELLED", null, latency);
                return new H3Snapshot("CANCELLED", null, null, failureReason(task));
            }
            default -> {
                // queued/running/processing 及一切未知状态：继续按在途轮询，绝不误判终态（16a A2.2）
                record(audit, endpoint, "RUNNING", null, latency);
                return new H3Snapshot("RUNNING", null, null, null);
            }
        }
    }

    @Override
    public Boolean probeUrlExpired(String url, ProviderCallAudit audit) {
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme())) {
            return null;
        }
        String endpoint = "result-head";
        long start = System.nanoTime();
        ContentProviderTransport.Response response;
        try {
            response = transport.exchange("HEAD", url, Map.of(), null, PROBE_TIMEOUT_SECONDS);
        } catch (ContentProviderTransport.NetworkFailure e) {
            record(audit, endpoint, e.isTimeout() ? "TIMEOUT" : "NETWORK_ERROR", null, elapsed(start));
            return null; // 网络故障不能误报失效
        } catch (IOException e) {
            record(audit, endpoint, "NETWORK_ERROR", null, elapsed(start));
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            record(audit, endpoint, "INTERRUPTED", null, elapsed(start));
            return null;
        }
        record(audit, endpoint, "HTTP_" + response.status(), null, elapsed(start));
        if (EXPIRED_STATUSES.contains(response.status())) {
            return true;
        }
        if (HEAD_UNSUPPORTED.contains(response.status())) {
            return null; // 部分对象存储不支持 HEAD：交由真实下载决定
        }
        return false;
    }

    // ---------------- 请求构造与契约校验（对标 validate_h3_request / _metaso_create_request） ----------------

    private void validate(H3Request request) {
        if (request.description() == null || request.description().isBlank()) {
            throw new ContentProviderException.Rejection("视频描述不能为空", 422, null);
        }
        if (request.description().length() > 7000) {
            throw new ContentProviderException.Rejection("视频描述超出 7000 字上限", 422, null);
        }
        if (!RESOLUTIONS.contains(request.resolution())) {
            throw new ContentProviderException.Rejection("分辨率仅支持 768P 或 2K", 422, null);
        }
        if (request.durationSeconds() < 4 || request.durationSeconds() > 15) {
            throw new ContentProviderException.Rejection("时长须为 4-15 秒的整数", 422, null);
        }
        if (!RATIOS.contains(request.ratio())) {
            throw new ContentProviderException.Rejection("视频比例不在支持范围内", 422, null);
        }
        boolean hasFrames = false;
        boolean hasReference = false;
        for (MediaRef ref : request.media() == null ? List.<MediaRef>of() : request.media()) {
            String role = ref.role();
            if (FRAME_ROLES.contains(role)) {
                hasFrames = true;
            } else if (REFERENCE_TYPES.containsKey(role)) {
                hasReference = true;
                if ("reference_image".equals(role) && (ref.name() == null || ref.name().isBlank())) {
                    throw new ContentProviderException.Rejection("参考图片必须提供用途名称", 422, null);
                }
            } else {
                throw new ContentProviderException.Rejection("不支持的首尾帧/参考素材类型：" + role, 422, null);
            }
            if (ref.url() == null || !ref.url().toLowerCase(Locale.ROOT).startsWith("https://")) {
                throw new ContentProviderException.Rejection("素材地址必须为 HTTPS 公网地址（供应商需据此拉取）", 422, null);
            }
        }
        if (hasFrames && hasReference) {
            throw new ContentProviderException.Rejection("首尾帧与参考素材不能混用", 422, null);
        }
        if (!hasFrames && "adaptive".equals(request.ratio())) {
            // T2V/R2V 禁 adaptive：无首尾帧时供应商无法推导比例（对标 ProviderRequestContractError）
            throw new ContentProviderException.Rejection("仅含首尾帧时可使用自适应比例，请选择具体画幅", 422, null);
        }
    }

    private ObjectNode buildRequest(H3Request request) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("model", MODEL);
        ArrayNode content = payload.putArray("content");
        content.addObject().put("type", "text").put("text", request.description());
        boolean hasFrames = false;
        if (request.media() != null) {
            for (MediaRef ref : request.media()) {
                ObjectNode element = content.addObject();
                if (FRAME_ROLES.contains(ref.role())) {
                    hasFrames = true;
                    element.put("type", "image_url");
                    element.putObject("image_url").put("url", ref.url());
                } else {
                    String mediaField = REFERENCE_TYPES.get(ref.role());
                    element.put("type", mediaField);
                    element.putObject(mediaField).put("url", ref.url());
                    if ("reference_image".equals(ref.role())) {
                        element.put("name", ref.name());
                    }
                }
                element.put("role", ref.role());
            }
        }
        payload.put("resolution", request.resolution());
        payload.put("duration", request.durationSeconds());
        // 含首尾帧强制 adaptive（后端归一，F11）
        payload.put("ratio", hasFrames ? "adaptive" : request.ratio());
        return payload;
    }

    private static JsonNode extractTask(JsonNode root, String providerTaskId) {
        JsonNode task = root.get("task");
        if (task != null && task.isObject()) {
            return task;
        }
        if (root.hasNonNull("status") || root.hasNonNull("id")) {
            return root;
        }
        // 历史列表形态：按 task_id 精确匹配，绝不把无关任务当结果（generation.py:7558-7576）
        for (JsonNode item : root.path("items")) {
            if (item.isObject() && providerTaskId.equals(item.path("id").asText())) {
                return item;
            }
        }
        return JSON.createObjectNode();
    }

    private static String failureReason(JsonNode task) {
        String code = task.path("fail_code").asText("");
        String message = task.path("fail_reason").asText(
                task.path("error").path("message").asText(""));
        if (!message.isBlank() && !code.isBlank()) {
            return message + "（" + code + "）";
        }
        return message.isBlank() ? code : message;
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
            recorder.record(audit.companyId(), audit.taskId(), "metaso", endpoint, latencyMs, resultCode, units);
        } else {
            log.debug("metaso {} result={} units={} latency={}ms (no recorder)", endpoint, resultCode, units, latencyMs);
        }
    }

}
