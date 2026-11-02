package cn.iocoder.yudao.module.franchise.content.provider;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DashScope Fun-ASR 双端点分流/提交即持久化/恢复轮询形状断言（16a A1，零真实外呼）。 */
class DashScopeAsrClientTest {

    private static ContentProviderProperties.DashScope config() {
        ContentProviderProperties.DashScope config = new ContentProviderProperties.DashScope();
        config.setApiKey("sk-test");
        config.setPollIntervalSeconds(0);
        config.setPollMaxAttempts(3);
        return config;
    }

    @Test
    void shortAudioGoesFlashSyncEndpoint() {
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200, """
                        {"output":{"text":"  短音频转写结果 "},"usage":{"duration":18}}""", 40));
        DashScopeAsrClient client = new DashScopeAsrClient(config(), transport, null);

        AsrPort.Transcript transcript = client.transcribe(new AsrPort.AsrCall(
                "https://cdn.example/audio.m4a", 120.0, null, null, ProviderCallAudit.NONE));

        assertThat(transcript.text()).isEqualTo("短音频转写结果");
        assertThat(transcript.durationSec()).isEqualTo(18.0);
        ContentProviderTransport.Scripted.Call call = transport.calls.get(0);
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.url()).isEqualTo("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation");
        assertThat(call.headers().get("Authorization")).isEqualTo("Bearer sk-test");
        assertThat(call.headers().get("X-DashScope-SSE")).isEqualTo("disable");
        assertThat(call.body()).asString(java.nio.charset.StandardCharsets.UTF_8).contains(
                "\"model\":\"fun-asr-flash-2026-06-15\"");
    }

    @Test
    void flashBodyCarriesAudioDataAndM4aParameters() {
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200, "{\"output\":{\"text\":\"ok\"}}", 10));
        DashScopeAsrClient client = new DashScopeAsrClient(config(), transport, null);

        client.transcribe(new AsrPort.AsrCall(
                "data:audio/mp4;base64,QUJD", 60.0, null, null, ProviderCallAudit.NONE));

        String body = new String(transport.calls.get(0).body(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("\"input_audio\":{\"data\":\"data:audio/mp4;base64,QUJD\"}");
        assertThat(body).contains("\"parameters\":{\"format\":\"m4a\",\"sample_rate\":\"16000\"}");
    }

    @Test
    void longAudioSubmitsAsyncPersistsTaskIdThenPollsAndDownloads() {
        List<String> submitted = new ArrayList<>();
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted(
                // 提交
                ContentProviderTransport.json(200, "{\"output\":{\"task_id\":\"asr-task-9\"}}", 20),
                // 第一次轮询 RUNNING、第二次 SUCCEEDED
                ContentProviderTransport.json(200, "{\"output\":{\"task_status\":\"RUNNING\"}}", 5),
                ContentProviderTransport.json(200, "{\"output\":{\"task_status\":\"SUCCEEDED\",\"results\":"
                        + "[{\"subtask_status\":\"SUCCEEDED\",\"transcription_url\":\"https://oss.example/t.json\"}]}}", 5),
                // 下载转写结果
                ContentProviderTransport.json(200, "{\"transcripts\":[{\"text\":\"长音频文案\"}],"
                        + "\"properties\":{\"original_duration_in_milliseconds\":420000}}", 30));
        DashScopeAsrClient client = new DashScopeAsrClient(config(), transport, null);

        AsrPort.Transcript transcript = client.transcribe(new AsrPort.AsrCall(
                "https://cdn.example/long.mp4", 420.0, null, submitted::add, ProviderCallAudit.NONE));

        assertThat(submitted).containsExactly("asr-task-9"); // 提交即持久化（A2.1）
        assertThat(transcript.text()).isEqualTo("长音频文案");
        assertThat(transcript.durationSec()).isEqualTo(420.0);
        var submit = transport.calls.get(0);
        assertThat(submit.method()).isEqualTo("POST");
        assertThat(submit.url()).isEqualTo("https://dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription");
        assertThat(submit.headers().get("X-DashScope-Async")).isEqualTo("enable");
        assertThat(submit.body()).asString(java.nio.charset.StandardCharsets.UTF_8)
                .contains("\"file_urls\":[\"https://cdn.example/long.mp4\"]")
                .contains("\"channel_id\":[0]");
        assertThat(transport.calls.get(1).url()).isEqualTo("https://dashscope.aliyuncs.com/api/v1/tasks/asr-task-9");
        assertThat(transport.calls.get(3).url()).isEqualTo("https://oss.example/t.json");
        assertThat(transport.calls.get(3).headers().get("Authorization")).isNull(); // 下载不带鉴权（对标 asr.py:408-414）
    }

    @Test
    void resumeWithExistingTaskIdNeverResubmits() {
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200, "{\"output\":{\"task_status\":\"SUCCEEDED\",\"results\":"
                        + "[{\"subtask_status\":\"SUCCEEDED\",\"transcription_url\":\"https://oss.example/r.json\"}]}}", 5),
                ContentProviderTransport.json(200, "{\"transcripts\":[{\"text\":\"恢复结果\"}]}", 10));
        DashScopeAsrClient client = new DashScopeAsrClient(config(), transport, null);

        AsrPort.Transcript transcript = client.transcribe(new AsrPort.AsrCall(
                "https://cdn.example/long.mp4", 420.0, "asr-task-9", null, ProviderCallAudit.NONE));

        assertThat(transcript.text()).isEqualTo("恢复结果");
        assertThat(transport.calls.get(0).url()).isEqualTo("https://dashscope.aliyuncs.com/api/v1/tasks/asr-task-9");
    }

    @Test
    void resumePollIgnoresMissingAudioUrl() {
        // 恢复轮询只凭 provider_task_id（audioUrl 为 null）——不得被新提交的输入校验误杀（评审十三 P1-1）
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200, "{\"output\":{\"task_status\":\"SUCCEEDED\",\"results\":"
                        + "[{\"subtask_status\":\"SUCCEEDED\",\"transcription_url\":\"https://oss.example/r.json\"}]}}", 5),
                ContentProviderTransport.json(200, "{\"transcripts\":[{\"text\":\"恢复结果\"}]}", 10));
        DashScopeAsrClient client = new DashScopeAsrClient(config(), transport, null);

        AsrPort.Transcript transcript = client.transcribe(new AsrPort.AsrCall(
                null, null, "asr-task-9", null, ProviderCallAudit.NONE));

        assertThat(transcript.text()).isEqualTo("恢复结果");
        assertThat(transport.calls.get(0).url()).isEqualTo("https://dashscope.aliyuncs.com/api/v1/tasks/asr-task-9");
    }

    @Test
    void submit5xxOrMissingTaskIdIsUncertain() {
        DashScopeAsrClient fiveHundred = new DashScopeAsrClient(config(),
                new ContentProviderTransport.Scripted(ContentProviderTransport.json(503, "busy", 5)), null);
        assertThatThrownBy(() -> fiveHundred.transcribe(new AsrPort.AsrCall(
                "https://cdn.example/long.mp4", 400.0, null, null, ProviderCallAudit.NONE)))
                .isInstanceOf(ContentProviderException.Uncertain.class);

        DashScopeAsrClient noTaskId = new DashScopeAsrClient(config(),
                new ContentProviderTransport.Scripted(ContentProviderTransport.json(200, "{\"output\":{}}", 5)), null);
        assertThatThrownBy(() -> noTaskId.transcribe(new AsrPort.AsrCall(
                "https://cdn.example/long.mp4", 400.0, null, null, ProviderCallAudit.NONE)))
                .isInstanceOf(ContentProviderException.Uncertain.class);
    }

    @Test
    void providerFailedStatusIsDefinitiveRejection() {
        DashScopeAsrClient client = new DashScopeAsrClient(config(),
                new ContentProviderTransport.Scripted(
                        ContentProviderTransport.json(200, "{\"output\":{\"task_id\":\"t1\"}}", 5),
                        ContentProviderTransport.json(200,
                                "{\"output\":{\"task_status\":\"FAILED\",\"results\":[{\"subtask_status\":\"FAILED\",\"message\":\"bad audio\"}]}}", 5)),
                null);

        assertThatThrownBy(() -> client.transcribe(new AsrPort.AsrCall(
                "https://cdn.example/long.mp4", 400.0, null, null, ProviderCallAudit.NONE)))
                .isInstanceOf(ContentProviderException.Rejection.class)
                .hasMessageContaining("语音转写任务失败");
    }

    @Test
    void invalidCredentialsAndUnconfiguredAreUnavailable() {
        DashScopeAsrClient unconfigured = new DashScopeAsrClient(
                new ContentProviderProperties.DashScope(), new ContentProviderTransport.Scripted(), null);
        assertThatThrownBy(() -> unconfigured.transcribe(new AsrPort.AsrCall(
                "https://cdn.example/a.m4a", 10.0, null, null, ProviderCallAudit.NONE)))
                .isInstanceOf(ContentProviderException.Unavailable.class)
                .hasMessageContaining("语音转写服务尚未配置");

        DashScopeAsrClient badCreds = new DashScopeAsrClient(config(),
                new ContentProviderTransport.Scripted(ContentProviderTransport.json(401, "denied", 5)), null);
        assertThatThrownBy(() -> badCreds.transcribe(new AsrPort.AsrCall(
                "https://cdn.example/a.m4a", 10.0, null, null, ProviderCallAudit.NONE)))
                .isInstanceOf(ContentProviderException.Unavailable.class)
                .hasMessageContaining("凭据无效");
    }

    @Test
    void nonPublicAudioUrlRejectedWithoutCall() {
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted();
        DashScopeAsrClient client = new DashScopeAsrClient(config(), transport, null);

        assertThatThrownBy(() -> client.transcribe(new AsrPort.AsrCall(
                "http://192.168.1.5/audio.m4a", 10.0, null, null, ProviderCallAudit.NONE)))
                .isInstanceOf(ContentProviderException.Rejection.class)
                .hasMessageContaining("无法读取此音频地址");
        assertThatThrownBy(() -> client.transcribe(new AsrPort.AsrCall(
                "https://localhost/audio.m4a", 10.0, null, null, ProviderCallAudit.NONE)))
                .isInstanceOf(ContentProviderException.Rejection.class);
        assertThat(transport.calls).isEmpty();
    }

}
