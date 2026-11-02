package cn.iocoder.yudao.module.franchise.content.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Metaso H3 提交/查询/白名单/UNCERTAIN/直链探测形状断言（16a A1+A2；零真实外呼）。 */
class MetasoH3ClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ContentProviderProperties.Metaso config() {
        ContentProviderProperties.Metaso config = new ContentProviderProperties.Metaso();
        config.setApiKey("mt-key");
        return config;
    }

    private static MetasoH3Client client(ContentProviderTransport.Scripted transport) {
        return new MetasoH3Client(config(), transport, null);
    }

    @Test
    void i2vSubmitShapeWithFirstFrameRatioNormalizedAdaptive() throws Exception {
        List<String> created = new ArrayList<>();
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200, "{\"task_id\":\"h3-1\"}", 60));

        String taskId = client(transport).submit(new VideoGenerationPort.H3Request(
                        "别墅航拍镜头", List.of(new VideoGenerationPort.MediaRef(
                                "first_frame", "https://cdn.example/f.jpg", null)),
                        "768P", 8, "16:9"),
                created::add, ProviderCallAudit.NONE);

        assertThat(taskId).isEqualTo("h3-1");
        assertThat(created).containsExactly("h3-1"); // 提交即持久化（A2.1）
        var call = transport.calls.get(0);
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.url()).isEqualTo("https://metaso.cn/api/minimax/v2/video_generation");
        assertThat(call.headers().get("Authorization")).isEqualTo("Bearer mt-key");
        JsonNode body = JSON.readTree(new String(call.body(), StandardCharsets.UTF_8));
        assertThat(body.path("model").asText()).isEqualTo("MiniMax-H3");
        assertThat(body.path("resolution").asText()).isEqualTo("768P");
        assertThat(body.path("duration").asInt()).isEqualTo(8);
        // 含首尾帧时 ratio 后端归一 adaptive（F11/D-1.5-6）
        assertThat(body.path("ratio").asText()).isEqualTo("adaptive");
        JsonNode content = body.path("content");
        assertThat(content.size()).isEqualTo(2);
        assertThat(content.get(0).path("type").asText()).isEqualTo("text");
        assertThat(content.get(0).path("text").asText()).isEqualTo("别墅航拍镜头");
        assertThat(content.get(1).path("type").asText()).isEqualTo("image_url");
        assertThat(content.get(1).path("role").asText()).isEqualTo("first_frame");
        assertThat(content.get(1).path("image_url").path("url").asText()).isEqualTo("https://cdn.example/f.jpg");
    }

    @Test
    void referenceMediaRolesAndT2vRatioPassthrough() throws Exception {
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200, "{\"task_id\":\"h3-2\"}", 60));
        client(transport).submit(new VideoGenerationPort.H3Request(
                        "纯文生视频", List.of(
                                new VideoGenerationPort.MediaRef("reference_image", "https://c/i.jpg", "参考图"),
                                new VideoGenerationPort.MediaRef("reference_video", "https://c/v.mp4", null),
                                new VideoGenerationPort.MediaRef("reference_audio", "https://c/a.mp3", null)),
                        "2K", 15, "9:16"),
                null, ProviderCallAudit.NONE);

        JsonNode content = JSON.readTree(new String(transport.calls.get(0).body(), StandardCharsets.UTF_8))
                .path("content");
        assertThat(content.size()).isEqualTo(4);
        assertThat(content.get(1).path("type").asText()).isEqualTo("image_url");
        assertThat(content.get(1).path("role").asText()).isEqualTo("reference_image");
        assertThat(content.get(1).path("name").asText()).isEqualTo("参考图");
        assertThat(content.get(2).path("type").asText()).isEqualTo("video_url");
        assertThat(content.get(2).path("video_url").path("url").asText()).isEqualTo("https://c/v.mp4");
        assertThat(content.get(3).path("type").asText()).isEqualTo("audio_url");
        // T2V/R2V 无首尾帧：ratio 原样透传
        assertThat(JSON.readTree(new String(transport.calls.get(0).body(), StandardCharsets.UTF_8))
                .path("ratio").asText()).isEqualTo("9:16");
    }

    @Test
    void contractViolationsRejectedBeforeCall() {
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted();
        MetasoH3Client client = client(transport);
        // T2V 禁 adaptive（对标 ProviderRequestContractError）
        assertThatThrownBy(() -> client.submit(new VideoGenerationPort.H3Request(
                        "文本", List.of(), "768P", 8, "adaptive"), null, ProviderCallAudit.NONE))
                .isInstanceOf(ContentProviderException.Rejection.class)
                .hasMessageContaining("仅含首尾帧时可使用自适应比例");
        // 时长越界
        assertThatThrownBy(() -> client.submit(new VideoGenerationPort.H3Request(
                        "文本", List.of(), "768P", 3, "16:9"), null, ProviderCallAudit.NONE))
                .isInstanceOf(ContentProviderException.Rejection.class)
                .hasMessageContaining("时长须为 4-15 秒");
        // 分辨率非法
        assertThatThrownBy(() -> client.submit(new VideoGenerationPort.H3Request(
                        "文本", List.of(), "1080P", 8, "16:9"), null, ProviderCallAudit.NONE))
                .isInstanceOf(ContentProviderException.Rejection.class)
                .hasMessageContaining("分辨率仅支持 768P 或 2K");
        // 媒体地址必须 HTTPS 公网（供应商侧无法拉取非 HTTPS）
        assertThatThrownBy(() -> client.submit(new VideoGenerationPort.H3Request(
                        "文本", List.of(new VideoGenerationPort.MediaRef("first_frame", "http://c/f.jpg", null)),
                        "768P", 8, "16:9"), null, ProviderCallAudit.NONE))
                .isInstanceOf(ContentProviderException.Rejection.class)
                .hasMessageContaining("素材地址必须为 HTTPS");
        assertThat(transport.calls).isEmpty();
    }

    @Test
    void rejectionWhitelistDefinitiveOthersUncertain() {
        // 402 在白名单：明确失败、未创建任务、未扣费（2026-10-13 停摆教训）
        List<String> created = new ArrayList<>();
        assertThatThrownBy(() -> client(new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(402, "payment required", 10))).submit(
                new VideoGenerationPort.H3Request("文本", List.of(), "768P", 8, "16:9"),
                created::add, ProviderCallAudit.NONE))
                .isInstanceOf(ContentProviderException.Rejection.class)
                .hasMessageContaining("本次未扣费");
        assertThat(created).isEmpty();

        // 500/网络类：可能已受理——UNCERTAIN，绝不自动重投
        assertThatThrownBy(() -> client(new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(500, "boom", 10))).submit(
                new VideoGenerationPort.H3Request("文本", List.of(), "768P", 8, "16:9"),
                null, ProviderCallAudit.NONE))
                .isInstanceOf(ContentProviderException.Uncertain.class);
        assertThatThrownBy(() -> client(new ContentProviderTransport.Scripted(false)).submit(
                new VideoGenerationPort.H3Request("文本", List.of(), "768P", 8, "16:9"),
                null, ProviderCallAudit.NONE))
                .isInstanceOf(ContentProviderException.Uncertain.class);
        // 2xx 但缺 task_id：同样可能已受理
        assertThatThrownBy(() -> client(new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200, "{}", 10))).submit(
                new VideoGenerationPort.H3Request("文本", List.of(), "768P", 8, "16:9"),
                null, ProviderCallAudit.NONE))
                .isInstanceOf(ContentProviderException.Uncertain.class);
    }

    @Test
    void pollReadsTaskShapesAndUnknownStatusStaysRunning() {
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted(
                // 官方 {task:{...}} 包装
                ContentProviderTransport.json(200, "{\"task\":{\"status\":\"succeeded\","
                        + "\"content\":{\"url\":\"https://cdn.example/out.mp4\"},\"usage\":{\"output_seconds\":8.5}}}", 10),
                // 裸任务对象 failed
                ContentProviderTransport.json(200, "{\"id\":\"h3-1\",\"status\":\"failed\",\"fail_reason\":\"敏感内容\"}", 10),
                // 未知状态 → RUNNING
                ContentProviderTransport.json(200, "{\"task\":{\"status\":\"weird\"}}", 10));
        MetasoH3Client client = client(transport);

        VideoGenerationPort.H3Snapshot succeeded = client.poll("h3-1", ProviderCallAudit.NONE);
        assertThat(succeeded.status()).isEqualTo("SUCCEEDED");
        assertThat(succeeded.resultUrl()).isEqualTo("https://cdn.example/out.mp4");
        assertThat(succeeded.outputSeconds()).isEqualTo(8.5);

        VideoGenerationPort.H3Snapshot failed = client.poll("h3-1", ProviderCallAudit.NONE);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failureReason()).contains("敏感内容");

        assertThat(client.poll("h3-1", ProviderCallAudit.NONE).status()).isEqualTo("RUNNING");
        assertThat(transport.calls.get(0).url())
                .isEqualTo("https://metaso.cn/api/minimax/v2/query/video_generation/h3-1");
    }

    @Test
    void succeededWithoutHttpsUrlIsTerminalRejection() {
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200, "{\"task\":{\"status\":\"succeeded\","
                        + "\"content\":{\"url\":\"http://insecure.example/out.mp4\"}}}", 10));
        assertThatThrownBy(() -> client(transport).poll("h3-1", ProviderCallAudit.NONE))
                .isInstanceOf(ContentProviderException.Rejection.class)
                .hasMessageContaining("未返回有效的 HTTPS 成片地址");
    }

    @Test
    void probeUrlExpiredByHeadStatus() {
        assertThat(client(new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(403, "", 5))).probeUrlExpired(
                "https://cdn.example/out.mp4", ProviderCallAudit.NONE)).isTrue();
        assertThat(client(new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200, "", 5))).probeUrlExpired(
                "https://cdn.example/out.mp4", ProviderCallAudit.NONE)).isFalse();
        // 405/501 = 对象存储不支持 HEAD：无法判断
        assertThat(client(new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(405, "", 5))).probeUrlExpired(
                "https://cdn.example/out.mp4", ProviderCallAudit.NONE)).isNull();
        // 网络/超时：不能误报失效
        assertThat(client(new ContentProviderTransport.Scripted(false)).probeUrlExpired(
                "https://cdn.example/out.mp4", ProviderCallAudit.NONE)).isNull();
        // 非 HTTPS 不探测
        assertThat(client(new ContentProviderTransport.Scripted()).probeUrlExpired(
                "http://x/y.mp4", ProviderCallAudit.NONE)).isNull();
    }

}
