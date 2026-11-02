package cn.iocoder.yudao.module.franchise.content.provider;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** fake 通道结局行为断言（ok/failed/uncertain；e2e 零付费外呼的基础）。 */
class FakeContentProvidersTest {

    private static FakeContentProviders fake(String outcome) {
        ContentProviderProperties properties = new ContentProviderProperties();
        properties.setProvider(ContentProviderProperties.ProviderMode.FAKE);
        properties.getFake().setOutcome(outcome);
        properties.getFake().setResultUrl("https://fixture.local/result.mp4");
        return new FakeContentProviders(properties, new RecordingRecorder());
    }

    @Test
    void okOutcomeDeliversAllFivePorts() {
        FakeContentProviders fake = fake("ok");

        assertThat(fake.linkParsePort().parse("https://v.douyin.com/x/", ProviderCallAudit.NONE).text())
                .contains("链接解析通道");
        assertThat(fake.asrPort().transcribe(new AsrPort.AsrCall("https://c/a.m4a", 100.0, null, null,
                ProviderCallAudit.NONE)).text()).contains("语音转写通道");
        assertThat(fake.scriptRewritePort().rewrite(new ScriptRewritePort.RewriteCall(
                "原文", null, null, ProviderCallAudit.NONE)).rewrittenText()).startsWith("fake 改写稿：原文");
        List<String> created = new ArrayList<>();
        String oralTaskId = fake.oralVideoPort().createByTts(new OralVideoPort.TtsRequest(
                "v", "t", "a", "标题", null, created::add, ProviderCallAudit.NONE));
        assertThat(oralTaskId).startsWith("fake-hifly-");
        assertThat(created).containsExactly(oralTaskId); // 提交即持久化回调
        assertThat(fake.oralVideoPort().poll(oralTaskId, ProviderCallAudit.NONE).videoUrl())
                .isEqualTo("https://fixture.local/result.mp4");
        assertThat(fake.videoGenerationPort().submit(new VideoGenerationPort.H3Request(
                "描述", List.of(), "768P", 8, "9:16"), null, ProviderCallAudit.NONE)).startsWith("fake-h3-");
        assertThat(fake.videoGenerationPort().poll("fake-h3-x", ProviderCallAudit.NONE).resultUrl())
                .isEqualTo("https://fixture.local/result.mp4");
        assertThat(fake.videoGenerationPort().probeUrlExpired("https://fixture.local/result.mp4",
                ProviderCallAudit.NONE)).isFalse();
    }

    @Test
    void failedOutcomeIsDefinitiveRejection() {
        assertThatThrownBy(() -> fake("failed").linkParsePort().parse("https://v.douyin.com/x/", ProviderCallAudit.NONE))
                .isInstanceOf(ContentProviderException.Rejection.class);
    }

    @Test
    void uncertainOutcomeNeverAutoResubmits() {
        List<String> created = new ArrayList<>();
        assertThatThrownBy(() -> fake("uncertain").oralVideoPort().createByTts(new OralVideoPort.TtsRequest(
                "v", "t", "a", "标题", null, created::add, ProviderCallAudit.NONE)))
                .isInstanceOf(ContentProviderException.Uncertain.class);
        assertThat(created).isEmpty(); // 未拿到任务号——由上层置 SUBMISSION_UNCERTAIN
    }

    static class RecordingRecorder extends ContentProviderCallRecorder {
        RecordingRecorder() {
            super(null);
        }
    }

}
