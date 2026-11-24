package cn.iocoder.yudao.module.franchise.content.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Official synchronous cover API. A missing creation receipt is uncertain, never safe to resubmit. */
public class HiflyCoverClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ContentProviderProperties.Hifly config;
    private final ContentProviderTransport transport;
    private final ContentProviderCallRecorder recorder;
    public record Template(String id, String title, String url) { }
    public record Templates(List<Template> list, int total) { }
    public record Cover(String id, String url) { }

    public HiflyCoverClient(ContentProviderProperties.Hifly config, ContentProviderTransport transport,
                            ContentProviderCallRecorder recorder) {
        this.config = config; this.transport = transport; this.recorder = recorder;
    }
    public boolean configured() { return !config.getApiKey().isBlank(); }

    public Templates templates(int page, int size, ProviderCallAudit audit) {
        JsonNode result = call("GET", "project/cover_styles?page=" + page + "&size=" + size, null, audit);
        if (!result.path("data").isArray() || !result.path("total").canConvertToInt())
            throw new ContentProviderException.Unavailable("封面模板暂不可用，请稍后重试");
        List<Template> templates = new ArrayList<>();
        for (JsonNode entry : result.path("data")) {
            String id = entry.path("id").asText("");
            String title = entry.path("title").asText("");
            String url = entry.path("url").asText("");
            if (id.isBlank() || id.length() > 200 || title.isBlank() || !CoverImageFetcher.safeUri(url))
                throw new ContentProviderException.Unavailable("封面模板信息暂不可用，请稍后重试");
            templates.add(new Template(id, title, url));
        }
        return new Templates(List.copyOf(templates), Math.max(0, result.path("total").asInt()));
    }

    public Cover create(String template, String title, String canvas, ProviderCallAudit audit) {
        JsonNode result = call("POST", "project/covers", Map.of("template", template, "content", title,
                "canvas_preset", canvas, "output_format", "jpg"), audit);
        JsonNode data = result.path("data").isObject() ? result.path("data") : result;
        String id = data.path("cover_id").asText("");
        String url = data.path("url").asText("");
        if (id.isBlank() || id.length() > 200 || !CoverImageFetcher.safeUri(url))
            throw new ContentProviderException.Uncertain("封面生成结果待核实，请勿重复生成");
        return new Cover(id, url);
    }

    private JsonNode call(String method, String path, Object body, ProviderCallAudit audit) {
        if (!configured()) throw new ContentProviderException.Unavailable("封面生成服务尚未配置，请联系平台");
        long start = System.nanoTime();
        String outcome = "NETWORK_ERROR";
        try {
            var response = transport.exchange(method, config.getBaseUrl() + "/api/v2/hifly/" + path,
                    Map.of("Authorization", "Bearer " + config.getApiKey(), "Content-Type", "application/json"),
                    body == null ? null : JSON.writeValueAsBytes(body), config.getTimeoutSeconds());
            if (response.status() == 401 || response.status() == 403) {
                outcome = "UNAVAILABLE";
                throw new ContentProviderException.Unavailable("封面生成服务暂不可用，请联系管理员");
            }
            if (response.status() < 200 || response.status() >= 300) throw new IllegalStateException("HTTP failure");
            JsonNode result = JSON.readTree(response.body());
            if (result == null || !result.path("code").isIntegralNumber()) throw new IllegalStateException("missing receipt");
            int code = result.path("code").asInt();
            if (code != 0) {
                outcome = "REJECTED";
                throw new ContentProviderException.Rejection(HiflyErrors.message(code), response.status(), Integer.toString(code));
            }
            outcome = "OK";
            return result;
        } catch (ContentProviderException error) { throw error;
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            if ("POST".equals(method)) throw new ContentProviderException.Uncertain("封面生成结果待核实，请勿重复生成");
            throw new ContentProviderException.Unavailable("封面模板暂不可用，请稍后重试");
        } finally {
            if (recorder != null) recorder.record(audit.companyId(), audit.taskId(), "hifly", path.split("\\?")[0],
                    (System.nanoTime() - start) / 1_000_000, outcome, null);
        }
    }
}
