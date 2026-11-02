package cn.iocoder.yudao.module.franchise.content.provider;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 豆一豆请求形状断言（16a A1 / 16 V3.1 勘正：GET + query 按字典序 + Sign 头=MD5(query+secret)）。
 * 全部注入假传输，零真实外呼。
 */
class DiadiLinkClientTest {

    private static ContentProviderProperties.Diadi config() {
        ContentProviderProperties.Diadi config = new ContentProviderProperties.Diadi();
        config.setAppId("app-1");
        config.setAppSecret("secret-1");
        return config;
    }

    private static String md5Hex(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    @Test
    void requestShapeGetWithSortedQueryAndMd5SignHeader() throws Exception {
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200, """
                        {"code":0,"data":{"video":[{"url":"https://cdn.example/v1.mp4"}],"text":"原文案",
                        "title":"示例标题","cover":"https://cdn.example/c.jpg","aweme_id":"7123456789012345678",
                        "author":{"nickname":"作者"},"other":{"duration":41000}}}""", 30));
        DiadiLinkClient client = new DiadiLinkClient(config(), transport, null);

        LinkParsePort.ParsedLink link = client.parse("https://v.douyin.com/abc~def/", ProviderCallAudit.NONE);

        ContentProviderTransport.Scripted.Call call = transport.calls.get(0);
        assertThat(call.method()).isEqualTo("GET");
        // 参数按字典序：app_id 在 url 前；~ 按 Python urlencode 规则不转义
        assertThat(call.url()).isEqualTo(
                "https://gateway.diadi.cn/api/parse?app_id=app-1&url=https%3A%2F%2Fv.douyin.com%2Fabc~def%2F");
        String query = call.url().substring(call.url().indexOf('?') + 1);
        assertThat(call.headers().get("Sign")).isEqualTo(md5Hex(query + "secret-1"));

        assertThat(link.platform()).isEqualTo("douyin");
        assertThat(link.videoUrl()).isEqualTo("https://cdn.example/v1.mp4");
        assertThat(link.text()).isEqualTo("原文案");
        assertThat(link.title()).isEqualTo("示例标题");
        assertThat(link.coverUrl()).isEqualTo("https://cdn.example/c.jpg");
        assertThat(link.author()).isEqualTo("作者");
        assertThat(link.durationMs()).isEqualTo(41000L);
    }

    @Test
    void xiaohongshuLinkExtractedFromRawText() throws Exception {
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200, """
                        {"code":0,"data":{"video":[{"url":"https://cdn.example/n.mp4"}],"note_id":"a1b2c3d4e5f60718293a4b5c",
                        "text":"小红书文案"}}""", 20));
        DiadiLinkClient client = new DiadiLinkClient(config(), transport, null);

        LinkParsePort.ParsedLink link = client.parse(
                "复制的分享口令 https://www.xiaohongshu.com/explore/a1b2c3d4e5f60718293a4b5c?xsec_token=tok 好看",
                ProviderCallAudit.NONE);

        assertThat(link.platform()).isEqualTo("xiaohongshu");
        assertThat(transport.calls.get(0).url()).contains("url=https%3A%2F%2Fwww.xiaohongshu.com%2Fexplore%2F");
    }

    @Test
    void unsupportedPlatformRejectedBeforeAnyCall() {
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted();
        DiadiLinkClient client = new DiadiLinkClient(config(), transport, null);

        assertThatThrownBy(() -> client.parse("https://channels.weixin.qq.com/x", ProviderCallAudit.NONE))
                .isInstanceOf(ContentProviderException.Rejection.class)
                .hasMessageContaining("视频号链接暂不支持解析");
        assertThatThrownBy(() -> client.parse("https://example.com/video/1", ProviderCallAudit.NONE))
                .isInstanceOf(ContentProviderException.Rejection.class)
                .hasMessageContaining("当前支持抖音和小红书");
        assertThat(transport.calls).isEmpty();
    }

    @Test
    void expiredBusinessCodeIsDefinitiveRejection() {
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200, "{\"code\":40001,\"message\":\"链接已过期\"}", 10));
        DiadiLinkClient client = new DiadiLinkClient(config(), transport, null);

        assertThatThrownBy(() -> client.parse("https://v.douyin.com/abc/", ProviderCallAudit.NONE))
                .isInstanceOf(ContentProviderException.Rejection.class)
                .hasMessageContaining("视频链接已过期");
    }

    @Test
    void http5xxIsUncertainButHttp4xxIsRejection() {
        DiadiLinkClient fiveHundred = new DiadiLinkClient(config(),
                new ContentProviderTransport.Scripted(ContentProviderTransport.json(502, "bad gateway", 10)), null);
        assertThatThrownBy(() -> fiveHundred.parse("https://v.douyin.com/abc/", ProviderCallAudit.NONE))
                .isInstanceOf(ContentProviderException.Uncertain.class);

        DiadiLinkClient fourTwentyTwo = new DiadiLinkClient(config(),
                new ContentProviderTransport.Scripted(ContentProviderTransport.json(422, "denied", 10)), null);
        assertThatThrownBy(() -> fourTwentyTwo.parse("https://v.douyin.com/abc/", ProviderCallAudit.NONE))
                .isInstanceOf(ContentProviderException.Rejection.class);
    }

    @Test
    void networkFailureIsUncertain() {
        DiadiLinkClient client = new DiadiLinkClient(config(),
                new ContentProviderTransport.Scripted(true), null);
        assertThatThrownBy(() -> client.parse("https://v.douyin.com/abc/", ProviderCallAudit.NONE))
                .isInstanceOf(ContentProviderException.Uncertain.class);
    }

    @Test
    void missingVideoUrlIsRejection() {
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200, "{\"code\":0,\"data\":{\"text\":\"仅文案\"}}", 10));
        DiadiLinkClient client = new DiadiLinkClient(config(), transport, null);

        assertThatThrownBy(() -> client.parse("https://v.douyin.com/abc/", ProviderCallAudit.NONE))
                .isInstanceOf(ContentProviderException.Rejection.class)
                .hasMessageContaining("链接中没有可用视频");
    }

    @Test
    void unconfiguredCredentialsAreUnavailable() {
        DiadiLinkClient client = new DiadiLinkClient(new ContentProviderProperties.Diadi(),
                new ContentProviderTransport.Scripted(), null);
        assertThatThrownBy(() -> client.parse("https://v.douyin.com/abc/", ProviderCallAudit.NONE))
                .isInstanceOf(ContentProviderException.Unavailable.class)
                .hasMessageContaining("链接解析服务尚未配置");
    }

    @Test
    void auditRowRecordedWithUnits() {
        RecordingRecorder recorder = new RecordingRecorder();
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200,
                        "{\"code\":0,\"data\":{\"video\":[{\"url\":\"https://c/v.mp4\"}],\"text\":\"t\","
                                + "\"aweme_id\":\"7123456789012345678\"}}", 25));
        DiadiLinkClient client = new DiadiLinkClient(config(), transport, recorder);

        client.parse("https://v.douyin.com/abc/", new ProviderCallAudit(9L, 77L));

        assertThat(recorder.rows).hasSize(1);
        assertThat(recorder.rows.get(0).provider()).isEqualTo("diadi");
        assertThat(recorder.rows.get(0).endpoint()).isEqualTo("api/parse");
        assertThat(recorder.rows.get(0).taskId()).isEqualTo(77L);
        assertThat(recorder.rows.get(0).resultCode()).isEqualTo("OK");
        assertThat(recorder.rows.get(0).unitsBilled()).isEqualTo(1);
    }

    /** 记录器假件：rows 供断言 */
    static class RecordingRecorder extends ContentProviderCallRecorder {
        record Row(Long companyId, Long taskId, String provider, String endpoint,
                   long latencyMs, String resultCode, Integer unitsBilled) {
        }

        List<Row> rows = new java.util.ArrayList<>();

        RecordingRecorder() {
            super(null);
        }

        @Override
        public void record(Long companyId, Long taskId, String provider, String endpoint,
                           long latencyMs, String resultCode, Integer unitsBilled) {
            rows.add(new Row(companyId, taskId, provider, endpoint, latencyMs, resultCode, unitsBilled));
        }
    }

}
