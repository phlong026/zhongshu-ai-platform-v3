package cn.iocoder.yudao.module.franchise.content.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * DashScope Fun-ASR（D-1.5-4 / 16a A1，对标 asr.py）：
 * ≤300s Flash 同步（X-DashScope-SSE: disable，超时 120s，m4a/16k 参数）；
 * 长音频异步提交（X-DashScope-Async: enable）→ onSubmitted 持久化任务号 → 2s×90 轮询 → 下载 transcription_url。
 * 韧性：existingTaskId 恢复轮询免二次付费；提交 ≥500/无任务号→Uncertain；轮询 5xx 继续等；
 * FAILED/CANCELED→Rejection；401/403→Unavailable。音频地址须公网 HTTPS 或 data: 前缀直传。
 */
public class DashScopeAsrClient implements AsrPort {

    private static final Logger log = LoggerFactory.getLogger(DashScopeAsrClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DATA_PREFIX = "data:audio/mp4;base64,";
    private static final int INLINE_MAX_BYTES = 10_000_000;
    private static final int FLASH_TIMEOUT_SECONDS = 120;
    private static final int SUBMIT_TIMEOUT_SECONDS = 30;
    private static final int POLL_TIMEOUT_SECONDS = 15;
    private static final int DOWNLOAD_TIMEOUT_SECONDS = 30;

    private final ContentProviderProperties.DashScope config;
    private final ContentProviderTransport transport;
    private final ContentProviderCallRecorder recorder;
    private final Sleeper sleeper;

    @FunctionalInterface
    public interface Sleeper {
        void sleep(double seconds) throws InterruptedException;

        Sleeper REAL = seconds -> Thread.sleep((long) (seconds * 1000));
    }

    public DashScopeAsrClient(ContentProviderProperties.DashScope config, ContentProviderTransport transport,
                              ContentProviderCallRecorder recorder) {
        this(config, transport, recorder, Sleeper.REAL);
    }

    public DashScopeAsrClient(ContentProviderProperties.DashScope config, ContentProviderTransport transport,
                              ContentProviderCallRecorder recorder, Sleeper sleeper) {
        this.config = config;
        this.transport = transport;
        this.recorder = recorder;
        this.sleeper = sleeper;
    }

    @Override
    public Transcript transcribe(AsrCall call) {
        if (config.getApiKey().isBlank()) {
            throw new ContentProviderException.Unavailable("语音转写服务尚未配置，请联系管理员");
        }
        try {
            if (call.existingTaskId() != null && !call.existingTaskId().isBlank()) {
                // 恢复轮询：已有供应商任务号，绝不再次付费提交（A2.7）。
                // 输入校验只约束新提交——恢复路径不带 audioUrl，不得误杀已付费在途任务（评审十三 P1-1）
                return resume(call.existingTaskId(), call.audit());
            }
            validateAudioInput(call.audioUrl());
            boolean flash = usesFlash(call.durationSec());
            if (flash) {
                return flashTranscribe(call, call.audit());
            }
            String taskId = submitAsync(call, call.audit());
            if (call.onSubmitted() != null) {
                call.onSubmitted().accept(taskId); // 提交即持久化：拿到任务号后、开轮询前（A2.1）
            }
            return resume(taskId, call.audit());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ContentProviderException.Uncertain("语音转写已中断，任务号已保存，稍后将恢复查询");
        } catch (ContentProviderException.Uncertain e) {
            // 已拿到任务号的轮询中断可恢复；提交不确定则保持 Uncertain（冻结计费不重投）
            throw e;
        }
    }

    private boolean usesFlash(Double durationSec) {
        return durationSec != null && durationSec > 0
                && durationSec <= Math.min(config.getFlashThresholdSeconds(), 300.0);
    }

    // ---------------- Flash 同步 ----------------

    private Transcript flashTranscribe(AsrCall call, ProviderCallAudit audit) throws InterruptedException {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("model", config.getFlashModel());
        ObjectNode message = payload.putObject("input").putArray("messages").addObject();
        message.put("role", "user");
        message.putArray("content").addObject().putObject("input_audio").put("data", call.audioUrl());
        payload.putObject("parameters").put("format", "m4a").put("sample_rate", "16000");

        String url = config.getBaseUrl() + "/api/v1/services/aigc/multimodal-generation/generation";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + config.getApiKey());
        headers.put("Content-Type", "application/json");
        headers.put("X-DashScope-SSE", "disable");

        ContentProviderTransport.Response response = exchange(
                "POST", url, headers, payload, FLASH_TIMEOUT_SECONDS, audit, "aigc/multimodal-generation/generation");
        JsonNode data = decode(response, audit);
        Double duration = confirmedDuration(data.path("usage").path("duration"), 1);
        String text = data.path("output").path("text").asText("").trim();
        if (text.isEmpty()) {
            record(audit, "aigc/multimodal-generation/generation", "EMPTY_TEXT", 1, startOf(response));
            throw new ContentProviderException.Rejection("语音转写服务返回空文本，请检查音频内容", response.status(), null);
        }
        record(audit, "aigc/multimodal-generation/generation", "OK", 1, startOf(response));
        return new Transcript(text, duration);
    }

    // ---------------- 异步提交-轮询-下载 ----------------

    private String submitAsync(AsrCall call, ProviderCallAudit audit) throws InterruptedException {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("model", config.getModel());
        ArrayNode urls = payload.putObject("input").putArray("file_urls");
        urls.add(call.audioUrl());
        payload.putObject("parameters").putArray("channel_id").add(0);

        String url = config.getBaseUrl() + "/api/v1/services/audio/asr/transcription";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + config.getApiKey());
        headers.put("Content-Type", "application/json");
        headers.put("X-DashScope-Async", "enable");

        ContentProviderTransport.Response response = exchange(
                "POST", url, headers, payload, SUBMIT_TIMEOUT_SECONDS, audit, "audio/asr/transcription");
        if (response.status() >= 500) {
            record(audit, "audio/asr/transcription", "HTTP_" + response.status(), null, startOf(response));
            throw new ContentProviderException.Uncertain("转写请求可能已受理，请稍后在任务中查看结果");
        }
        JsonNode data = decode(response, audit);
        String taskId = data.path("output").path("task_id").asText("");
        if (taskId.isBlank()) {
            record(audit, "audio/asr/transcription", "TASK_ID_MISSING", null, startOf(response));
            throw new ContentProviderException.Uncertain("转写服务未返回有效任务号，请稍后在任务中查看结果");
        }
        record(audit, "audio/asr/transcription", "SUBMITTED", null, startOf(response));
        return taskId;
    }

    private Transcript resume(String taskId, ProviderCallAudit audit) throws InterruptedException {
        String pollUrl = config.getBaseUrl() + "/api/v1/tasks/" + taskId;
        for (int attempt = 1; attempt <= config.getPollMaxAttempts(); attempt++) {
            sleeper.sleep(config.getPollIntervalSeconds());
            ContentProviderTransport.Response response;
            try {
                response = transport.exchange("GET", pollUrl,
                        Map.of("Authorization", "Bearer " + config.getApiKey()), null, POLL_TIMEOUT_SECONDS);
            } catch (ContentProviderTransport.NetworkFailure e) {
                record(audit, "tasks/query", e.isTimeout() ? "TIMEOUT" : "NETWORK_ERROR", null, 0);
                continue; // 网络瞬断继续等（对标 asr._poll_async_task 5xx continue）
            } catch (IOException e) {
                record(audit, "tasks/query", "NETWORK_ERROR", null, 0);
                continue;
            }
            if (response.status() >= 500) {
                record(audit, "tasks/query", "HTTP_" + response.status(), null, response.elapsedMs());
                continue;
            }
            JsonNode data;
            try {
                data = JSON.readTree(response.body());
            } catch (IOException e) {
                record(audit, "tasks/query", "PARSE_ERROR", null, response.elapsedMs());
                continue;
            }
            if (response.status() == 401 || response.status() == 403) {
                record(audit, "tasks/query", "HTTP_" + response.status(), null, response.elapsedMs());
                throw new ContentProviderException.Unavailable("语音转写服务凭据无效或无权限，请联系管理员");
            }
            String status = data.path("output").path("task_status").asText("");
            if ("SUCCEEDED".equals(status)) {
                record(audit, "tasks/query", "SUCCEEDED", null, response.elapsedMs());
                return download(data.path("output"), audit);
            }
            if ("FAILED".equals(status) || "CANCELED".equals(status)) {
                String message = "";
                for (JsonNode item : data.path("output").path("results")) {
                    if ("FAILED".equals(item.path("subtask_status").asText())) {
                        message = item.path("message").asText(item.path("code").asText(""));
                        break;
                    }
                }
                record(audit, "tasks/query", status, null, response.elapsedMs());
                throw new ContentProviderException.Rejection(
                        "语音转写任务失败：" + (message.isBlank() ? status : message), response.status(), null);
            }
            record(audit, "tasks/query", "RUNNING", null, response.elapsedMs());
        }
        // 轮询超窗：任务仍在途——交由上层任务机继续恢复查询，不判死、不重复提交
        throw new ContentProviderException.Uncertain("语音转写任务仍在处理中，请稍后在任务中查看结果");
    }

    private Transcript download(JsonNode output, ProviderCallAudit audit) throws InterruptedException {
        String transcriptionUrl = null;
        for (JsonNode item : output.path("results")) {
            if ("SUCCEEDED".equals(item.path("subtask_status").asText())
                    && !item.path("transcription_url").asText("").isBlank()) {
                transcriptionUrl = item.path("transcription_url").asText();
                break;
            }
        }
        if (transcriptionUrl == null) {
            throw new ContentProviderException.Rejection("语音转写服务未返回可用的转写结果", 200, null);
        }
        ContentProviderTransport.Response response;
        try {
            response = transport.exchange("GET", transcriptionUrl, Map.of(), null, DOWNLOAD_TIMEOUT_SECONDS);
        } catch (ContentProviderTransport.NetworkFailure e) {
            record(audit, "transcription/download", e.isTimeout() ? "TIMEOUT" : "NETWORK_ERROR", null, 0);
            throw new ContentProviderException.Uncertain("转写结果下载暂时中断，请稍后在任务中查看结果");
        } catch (IOException e) {
            record(audit, "transcription/download", "NETWORK_ERROR", null, 0);
            throw new ContentProviderException.Uncertain("转写结果下载暂时中断，请稍后在任务中查看结果");
        }
        JsonNode data;
        try {
            data = JSON.readTree(response.body());
        } catch (IOException e) {
            record(audit, "transcription/download", "PARSE_ERROR", null, response.elapsedMs());
            throw new ContentProviderException.Rejection("语音转写结果解析失败，请稍后重试", response.status(), null);
        }
        Double duration = confirmedDuration(
                data.path("properties").path("original_duration_in_milliseconds"), 1000);
        String text = data.path("transcripts").path(0).path("text").asText("").trim();
        if (text.isEmpty()) {
            record(audit, "transcription/download", "EMPTY_TEXT", 1, response.elapsedMs());
            throw new ContentProviderException.Rejection("语音转写返回空文本，请检查音频内容", response.status(), null);
        }
        record(audit, "transcription/download", "OK", 1, response.elapsedMs());
        return new Transcript(text, duration);
    }

    // ---------------- 输入校验（对标 asr._validate_audio_input） ----------------

    private void validateAudioInput(String audioUrl) {
        if (audioUrl == null || audioUrl.isBlank()) {
            throw new ContentProviderException.Rejection("请先上传音频或视频素材", 422, null);
        }
        if (audioUrl.startsWith(DATA_PREFIX)) {
            if (audioUrl.length() > INLINE_MAX_BYTES) {
                throw new ContentProviderException.Rejection("本地音频超出直传限制，请使用云存储链接", 422, null);
            }
            return;
        }
        URI uri;
        try {
            uri = URI.create(audioUrl);
        } catch (IllegalArgumentException e) {
            uri = null;
        }
        String host = uri == null ? null : uri.getHost();
        if (host == null || uri.getUserInfo() != null
                || (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()))) {
            throw new ContentProviderException.Rejection("转写服务无法读取此音频地址，请使用公网可访问链接", 422, null);
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".local")) {
            throw new ContentProviderException.Rejection("转写服务无法读取此音频地址，请使用公网可访问链接", 422, null);
        }
        if (isPrivateLiteral(lower)) {
            throw new ContentProviderException.Rejection("转写服务无法读取此音频地址，请使用公网可访问链接", 422, null);
        }
    }

    /** 仅拒 IP 字面量（对标 asr.py 的 ip_address 判定）；域名不在此做 DNS 解析，交上游判断 */
    private static boolean isPrivateLiteral(String host) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matcher(host);
        if (!matcher.matches()) {
            return false;
        }
        int first = Integer.parseInt(matcher.group(1));
        int second = Integer.parseInt(matcher.group(2));
        return first == 10 || first == 127 || first == 0
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168);
    }

    // ---------------- 公共 ----------------

    private ContentProviderTransport.Response exchange(String method, String url, Map<String, String> headers,
                                                        ObjectNode payload, int timeoutSeconds,
                                                        ProviderCallAudit audit, String endpoint)
            throws InterruptedException {
        try {
            return transport.exchange(method, url, headers,
                    payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), timeoutSeconds);
        } catch (ContentProviderTransport.NetworkFailure e) {
            record(audit, endpoint, e.isTimeout() ? "TIMEOUT" : "NETWORK_ERROR", null, 0);
            throw new ContentProviderException.Uncertain("语音转写服务连接暂时中断，请稍后在任务中查看结果");
        } catch (IOException e) {
            record(audit, endpoint, "NETWORK_ERROR", null, 0);
            throw new ContentProviderException.Uncertain("语音转写服务连接暂时中断，请稍后在任务中查看结果");
        }
    }

    private JsonNode decode(ContentProviderTransport.Response response, ProviderCallAudit audit) {
        if (response.status() == 401 || response.status() == 403) {
            throw new ContentProviderException.Unavailable("语音转写服务凭据无效或无权限，请联系管理员");
        }
        if (response.status() == 429 || response.status() >= 500) {
            throw new ContentProviderException.Unavailable("语音转写服务暂不可用，请稍后重试");
        }
        if (response.status() >= 400) {
            throw new ContentProviderException.Rejection(
                    "语音转写请求未被接受（HTTP " + response.status() + "），请检查音频格式与时长", response.status(), null);
        }
        try {
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new ContentProviderException.Rejection("语音转写服务响应解析失败，请稍后重试", response.status(), null);
        }
    }

    private static Double confirmedDuration(JsonNode raw, double divisor) {
        if (raw == null || raw.isNull() || raw.isBoolean()) {
            return null;
        }
        double value = raw.asDouble(Double.NaN);
        if (Double.isNaN(value) || !Double.isFinite(value) || value <= 0) {
            return null;
        }
        return value / divisor;
    }

    private void record(ProviderCallAudit audit, String endpoint, String resultCode, Integer units, long elapsedMs) {
        if (recorder != null) {
            recorder.record(audit.companyId(), audit.taskId(), "dashscope", endpoint, elapsedMs, resultCode, units);
        } else {
            log.debug("dashscope {} result={} units={} latency={}ms (no recorder)", endpoint, resultCode, units, elapsedMs);
        }
    }

    private static long startOf(ContentProviderTransport.Response response) {
        return response.elapsedMs();
    }

}
