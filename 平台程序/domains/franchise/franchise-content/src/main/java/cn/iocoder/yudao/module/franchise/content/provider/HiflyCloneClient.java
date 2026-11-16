package cn.iocoder.yudao.module.franchise.content.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** 复用数字人口播通道；创建不确定时不得重投，刷新只查询任务。 */
public class HiflyCloneClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ContentProviderProperties.Hifly config;
    private final ContentProviderTransport transport;
    private final ContentProviderCallRecorder recorder;
    public record Result(String status, String resourceId, String demoUrl) { }
    public record UploadGrant(String url,String contentType,String fileId) { }

    public HiflyCloneClient(ContentProviderProperties.Hifly config, ContentProviderTransport transport, ContentProviderCallRecorder recorder) {
        this.config = config; this.transport = transport; this.recorder = recorder;
    }
    public boolean configured() { return !config.getApiKey().isBlank(); }
    public UploadGrant uploadGrant(String extension,ProviderCallAudit audit) {
        JsonNode data=call("POST","tool/create_upload_url",Map.of("file_extension",extension),audit);
        String url=data.path("upload_url").asText(""),type=data.path("content_type").asText(""),file=data.path("file_id").asText("");
        try {
            var uri=java.net.URI.create(url);
            if(!"https".equalsIgnoreCase(uri.getScheme())||!"upload.hifly.cc".equalsIgnoreCase(uri.getHost())
                    ||uri.getRawUserInfo()!=null||uri.getPort()!=-1||file.isBlank()||file.length()>200
                    ||!("video/mp4".equals(type)||"audio/mpeg".equals(type)||"audio/mp3".equals(type)))throw new IllegalArgumentException();
        }catch(RuntimeException e){throw new ContentProviderException.Unavailable("素材直传通道暂不可用，请稍后重试");}
        return new UploadGrant(url,type,file);
    }
    public String createByFile(String kind,String title,String fileId,ProviderCallAudit audit) {
        var payload="AVATAR".equals(kind)?Map.<String,Object>of("title",title,"file_id",fileId,"aigc_flag",1)
                :Map.<String,Object>of("title",title,"file_id",fileId,"voice_type",8);
        JsonNode data=call("POST","AVATAR".equals(kind)?"avatar/create_by_video":"voice/create",payload,audit);
        String taskId=data.path("task_id").asText("");
        if(taskId.isBlank())throw new ContentProviderException.Uncertain("制作提交结果待核实，请勿重复制作");
        return taskId;
    }
    public String create(String kind, String title, String sourceUrl, ProviderCallAudit audit) {
        boolean avatar = "AVATAR".equals(kind);
        var payload = avatar ? Map.<String,Object>of("title", title, "video_url", sourceUrl, "aigc_flag", 1)
                : Map.<String,Object>of("title", title, "audio_url", sourceUrl, "voice_type", 8);
        JsonNode data = call("POST", avatar ? "avatar/create_by_video" : "voice/create", payload, audit);
        String taskId = data.path("task_id").asText("");
        if (taskId.isBlank()) throw new ContentProviderException.Uncertain("制作提交结果待核实，请勿重复制作");
        return taskId;
    }
    public Result poll(String kind, String taskId, ProviderCallAudit audit) {
        JsonNode data = call("GET", ("AVATAR".equals(kind) ? "avatar/task" : "voice/task") + "?task_id="
                + URLEncoder.encode(taskId, StandardCharsets.UTF_8), null, audit);
        String resource = "AVATAR".equals(kind) ? data.path("avatar").asText(data.path("avatar_id").asText(""))
                : data.path("voice").asText(data.path("voice_id").asText(""));
        String status = switch (data.path("status").asInt(-1)) { case 3 -> "READY"; case 4 -> "FAILED"; case 1,2 -> "RUNNING"; default -> "UNKNOWN"; };
        if ("READY".equals(status) && resource.isBlank()) status = "UNKNOWN";
        return new Result(status, resource, data.path("demo_url").asText(data.path("audio_url").asText("")));
    }
    private JsonNode call(String method, String path, Object body, ProviderCallAudit audit) {
        if (!configured()) throw new ContentProviderException.Unavailable("数字人服务尚未配置，请联系平台");
        long start = System.nanoTime();
        String outcome = "NETWORK_ERROR";
        try {
            var response = transport.exchange(method, config.getBaseUrl() + "/api/v2/hifly/" + path,
                    Map.of("Authorization", "Bearer " + config.getApiKey(), "Content-Type", "application/json"),
                    body == null ? null : JSON.writeValueAsBytes(body), config.getTimeoutSeconds());
            JsonNode envelope = JSON.readTree(response.body());
            JsonNode code = envelope == null ? null : envelope.get("code");
            // 官方直传凭证成功回包没有code；其他接口仍必须提供明确业务回执。
            if ((code == null && !"tool/create_upload_url".equals(path)) || (code != null && !code.isIntegralNumber())) throw new IllegalStateException("missing business receipt");
            if (code != null && code.asInt() != 0) {
                outcome = "REJECTED";
                throw new ContentProviderException.Rejection("数字人制作未受理，请检查素材、服务权限或联系管理员", response.status(), code.asText());
            }
            if (response.status() < 200 || response.status() >= 300) throw new IllegalStateException("invalid HTTP receipt");
            outcome = "OK";
            // 官网将任务号、状态放在根层；兼容历史网关的 data 包装。
            JsonNode data = envelope.get("data");
            return data != null && data.isObject() ? data : envelope;
        } catch (ContentProviderException e) { throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            if ("tool/create_upload_url".equals(path)) throw new ContentProviderException.Unavailable("素材直传通道暂不可用，请稍后重试");
            if ("POST".equals(method)) throw new ContentProviderException.Uncertain("制作提交结果待核实，请勿重复制作");
            throw new ContentProviderException.Unavailable("制作状态暂不可用，请稍后刷新");
        } finally {
            if (recorder != null) recorder.record(audit.companyId(), audit.taskId(), "hifly", path.split("\\?")[0],
                    (System.nanoTime()-start)/1_000_000, outcome, null);
        }
    }
}
