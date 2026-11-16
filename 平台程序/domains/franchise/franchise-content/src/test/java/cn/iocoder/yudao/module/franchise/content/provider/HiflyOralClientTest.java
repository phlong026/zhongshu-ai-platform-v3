package cn.iocoder.yudao.module.franchise.content.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HiFly 数字人提交/轮询形状断言（16a A1：create_by_tts、状态 1/2/3/4、video_Url 拼写怪癖；零真实外呼）。 */
class HiflyOralClientTest {
    @Test
    void officialRootLevelVideoResultCompletesWithPlayableUrl() {
        var transport=new ContentProviderTransport.Scripted(ContentProviderTransport.json(200,
                "{\"code\":0,\"status\":3,\"video_Url\":\"https://cdn.example/official.mp4\",\"duration\":62}",0));
        var result=new HiflyOralClient(config(),transport,null).poll("official-task",ProviderCallAudit.NONE);
        assertThat(result.status()).isEqualTo("DONE");
        assertThat(result.videoUrl()).isEqualTo("https://cdn.example/official.mp4");
        assertThat(result.durationSec()).isEqualTo(62);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void completionWithoutPlayableUrlRemainsPendingVerification() {
        for(String url:java.util.List.of("","javascript:alert(1)","not-a-video-url")) {
            var transport=new ContentProviderTransport.Scripted(ContentProviderTransport.json(200,
                    "{\"code\":0,\"status\":3,\"video_Url\":\""+url+"\"}",0));
            assertThat(new HiflyOralClient(config(),transport,null).poll("task",ProviderCallAudit.NONE).status()).isEqualTo("UNKNOWN");
        }
    }

    private static ContentProviderProperties.Hifly config() {
        ContentProviderProperties.Hifly config = new ContentProviderProperties.Hifly();
        config.setApiKey("hf-key");
        return config;
    }

    @Test
    void createByTtsPayloadShapeAndTaskIdCallback() throws Exception {
        java.util.List<String> created = new java.util.ArrayList<>();
        Map<String, Object> subtitle = new LinkedHashMap<>();
        subtitle.put("st_show", 1);
        subtitle.put("st_font_size", 32);
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200, "{\"code\":0,\"data\":{\"task_id\":\"hf-task-1\"}}", 50));

        String taskId = new HiflyOralClient(config(), transport, null).createByTts(new OralVideoPort.TtsRequest(
                "voice-9", "口播全文", "avatar-3", "我的口播作品", subtitle, created::add, ProviderCallAudit.NONE));

        assertThat(taskId).isEqualTo("hf-task-1");
        assertThat(created).containsExactly("hf-task-1"); // 提交即持久化（A2.1）
        var call = transport.calls.get(0);
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.url()).isEqualTo("https://hfw-api.hifly.cc/api/v2/hifly/video/create_by_tts");
        assertThat(call.headers().get("Authorization")).isEqualTo("Bearer hf-key");
        JsonNode body = JSON.readTree(new String(call.body(), StandardCharsets.UTF_8));
        assertThat(body.path("voice").asText()).isEqualTo("voice-9");
        assertThat(body.path("text").asText()).isEqualTo("口播全文");
        assertThat(body.path("avatar").asText()).isEqualTo("avatar-3");
        assertThat(body.path("aigc_flag").asInt()).isEqualTo(1);
        assertThat(body.path("st_show").asInt()).isEqualTo(1); // 字幕平铺进 payload（对标 hifly.py:586-588）
        assertThat(body.path("st_font_size").asInt()).isEqualTo(32);
    }

    @Test
    void longTitleClippedToTwentyChars() throws Exception {
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200, "{\"code\":0,\"data\":{\"task_id\":\"t\"}}", 10));
        new HiflyOralClient(config(), transport, null).createByTts(new OralVideoPort.TtsRequest(
                "v", "t", "a", "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十", null, null, ProviderCallAudit.NONE));

        JsonNode body = JSON.readTree(new String(transport.calls.get(0).body(), StandardCharsets.UTF_8));
        assertThat(body.path("title").asText()).hasSize(20);
    }

    @Test
    void vendorBusinessCodeIsDefinitiveRejection() {
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200, "{\"code\":1002,\"message\":\"credit not enough\"}", 10));
        assertThatThrownBy(() -> new HiflyOralClient(config(), transport, null).createByTts(new OralVideoPort.TtsRequest(
                "v", "t", "a", "标题", null, null, ProviderCallAudit.NONE)))
                .isInstanceOf(ContentProviderException.Rejection.class)
                .hasMessageContaining("余额不足")
                .hasFieldOrPropertyWithValue("vendorCode", "1002");
    }

    @Test
    void networkFailureOnCreateIsUncertain() {
        assertThatThrownBy(() -> new HiflyOralClient(config(),
                new ContentProviderTransport.Scripted(false), null).createByTts(new OralVideoPort.TtsRequest(
                "v", "t", "a", "标题", null, null, ProviderCallAudit.NONE)))
                .isInstanceOf(ContentProviderException.Uncertain.class)
                .hasMessageContaining("提交结果未知");
    }

    @Test
    void pollMapsVendorStatusesAndVideoUrlQuirk() {
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200,
                        // 供应商字段名怪癖：video_Url 大写 U（hifly.py:614）
                        "{\"code\":0,\"data\":{\"status\":3,\"video_Url\":\"https://cdn.example/oral.mp4\",\"duration\":62}}", 10),
                ContentProviderTransport.json(200, "{\"code\":0,\"data\":{\"status\":2}}", 5),
                ContentProviderTransport.json(200, "{\"code\":0,\"data\":{\"status\":4}}", 5),
                ContentProviderTransport.json(200, "{\"code\":0,\"data\":{\"status\":99}}", 5));
        HiflyOralClient client = new HiflyOralClient(config(), transport, null);

        OralVideoPort.Snapshot done = client.poll("t1", ProviderCallAudit.NONE);
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.videoUrl()).isEqualTo("https://cdn.example/oral.mp4");
        assertThat(done.durationSec()).isEqualTo(62);
        assertThat(client.poll("t1", ProviderCallAudit.NONE).status()).isEqualTo("PROCESSING");
        assertThat(client.poll("t1", ProviderCallAudit.NONE).status()).isEqualTo("FAILED");
        assertThat(client.poll("t1", ProviderCallAudit.NONE).status()).isEqualTo("UNKNOWN"); // 未知按在途
        assertThat(transport.calls.get(0).url())
                .isEqualTo("https://hfw-api.hifly.cc/api/v2/hifly/video/task?task_id=t1");
    }

    @Test
    void emptyTextRejectedBeforeCall() {
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted();
        assertThatThrownBy(() -> new HiflyOralClient(config(), transport, null).createByTts(
                new OralVideoPort.TtsRequest("v", "  ", "a", "标题", null, null, ProviderCallAudit.NONE)))
                .isInstanceOf(ContentProviderException.Rejection.class)
                .hasMessageContaining("口播文案不能为空");
        assertThatThrownBy(() -> new HiflyOralClient(config(), transport, null).createByTts(
                new OralVideoPort.TtsRequest("v", "字".repeat(10001), "a", "标题", null, null, ProviderCallAudit.NONE)))
                .isInstanceOf(ContentProviderException.Rejection.class)
                .hasMessageContaining("超出 10000 字上限");
        assertThat(transport.calls).isEmpty();
    }

    @Test
    void unconfiguredIsUnavailable() {
        assertThatThrownBy(() -> new HiflyOralClient(new ContentProviderProperties.Hifly(),
                new ContentProviderTransport.Scripted(), null).createByTts(new OralVideoPort.TtsRequest(
                "v", "t", "a", "标题", null, null, ProviderCallAudit.NONE)))
                .isInstanceOf(ContentProviderException.Unavailable.class)
                .hasMessageContaining("数字人口播服务尚未配置");
    }

}
