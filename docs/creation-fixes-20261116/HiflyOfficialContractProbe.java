import cn.iocoder.yudao.module.franchise.content.provider.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Read-only contract probe. Scripted transport only: no real provider request or charge. */
public class HiflyOfficialContractProbe {
    static final List<Map<String,Object>> results = new ArrayList<>();
    static ContentProviderProperties.Hifly config() {
        var config = new ContentProviderProperties.Hifly();
        config.setApiKey("contract-probe-not-a-real-key"); return config;
    }
    static ContentProviderTransport.Scripted response(String json) {
        return new ContentProviderTransport.Scripted(ContentProviderTransport.json(200, json, 0));
    }
    public static void main(String[] args) throws Exception {
        for (String kind : List.of("AVATAR", "VOICE")) {
            try {
                String task = new HiflyCloneClient(config(), response("{\"code\":0,\"message\":\"\",\"task_id\":\"official-task\"}"), null)
                        .create(kind, "contract sample", "https://fixture.example/media", ProviderCallAudit.NONE);
                results.add(Map.of("operation", kind + " create", "expected", "official-task", "actual", task));
            } catch (ContentProviderException e) {
                results.add(Map.of("operation", kind + " create", "expected", "official-task", "actual", e.getClass().getSimpleName()));
            }
            String resource = kind.equals("AVATAR") ? "avatar" : "voice";
            var completed = new HiflyCloneClient(config(), response("{\"code\":0,\"status\":3,\"" + resource + "\":\"official-resource\",\"demo_url\":\"https://fixture.example/demo.mp3\"}"), null)
                    .poll(kind, "official-task", ProviderCallAudit.NONE);
            results.add(Map.of("operation", kind + " poll", "expected", "READY", "actual", completed.status(), "resourceId", completed.resourceId()));
        }
        var video = new HiflyOralClient(config(), response("{\"code\":0,\"status\":3,\"video_Url\":\"https://fixture.example/video.mp4\",\"duration\":62}"), null)
                .poll("official-task", ProviderCallAudit.NONE);
        results.add(Map.of("operation", "digital video poll", "expected", "DONE with video URL", "actual", video.status(), "videoUrl", String.valueOf(video.videoUrl())));
        var control = new HiflyCloneClient(config(), response("{\"code\":0,\"data\":{\"task_id\":\"nested-control\"}}"), null)
                .create("AVATAR", "control", "https://fixture.example/video.mp4", ProviderCallAudit.NONE);
        results.add(Map.of("operation", "existing nested test fixture", "expected", "nested-control", "actual", control));
        System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }
}
