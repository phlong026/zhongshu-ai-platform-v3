package cn.iocoder.yudao.module.franchise.content.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DeepSeek 改写请求形状断言（16a A1 + V3.1 勘正 temperature=1.3；零真实外呼）。 */
class DeepSeekChatClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ContentProviderProperties.DeepSeek config() {
        ContentProviderProperties.DeepSeek config = new ContentProviderProperties.DeepSeek();
        config.setApiKey("sk-ds");
        return config;
    }

    private static DeepSeekChatClient client(ContentProviderTransport.Scripted transport) {
        return new DeepSeekChatClient(config(), transport, null);
    }

    @Test
    void requestShapeSystemPersonaRequirementSource() throws Exception {
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200, """
                        {"choices":[{"message":{"content":" 改写后的口播稿 "},"finish_reason":"stop"}]}""", 800));
        ScriptRewritePort.Result result = client(transport).rewrite(new ScriptRewritePort.RewriteCall(
                "原文全文", "更口语化", "人设：乡墅顾问张工", ProviderCallAudit.NONE));

        assertThat(result.rewrittenText()).isEqualTo("改写后的口播稿");
        assertThat(result.model()).isEqualTo("deepseek-chat");

        var call = transport.calls.get(0);
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.url()).isEqualTo("https://api.deepseek.com/chat/completions");
        assertThat(call.headers().get("Authorization")).isEqualTo("Bearer sk-ds");
        JsonNode body = JSON.readTree(new String(call.body(), StandardCharsets.UTF_8));
        assertThat(body.path("model").asText()).isEqualTo("deepseek-chat");
        assertThat(body.path("stream").asBoolean()).isFalse();
        assertThat(body.path("temperature").asDouble()).isEqualTo(1.3);
        assertThat(body.path("max_tokens").asInt()).isEqualTo(8192);
        assertThat(body.path("messages").size()).isEqualTo(4);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").get(0).path("content").asText()).startsWith("你是乡墅行业短视频口播稿编辑");
        assertThat(body.path("messages").get(1).path("content").asText()).contains("人设：乡墅顾问张工");
        assertThat(body.path("messages").get(2).path("content").asText()).contains("更口语化");
        assertThat(body.path("messages").get(3).path("content").asText()).contains("原文全文");
    }

    @Test
    void minimalCallHasSystemPlusSourceOnly() throws Exception {
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200,
                        "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}", 10));
        client(transport).rewrite(new ScriptRewritePort.RewriteCall("原文", null, null, ProviderCallAudit.NONE));

        JsonNode body = JSON.readTree(new String(transport.calls.get(0).body(), StandardCharsets.UTF_8));
        assertThat(body.path("messages").size()).isEqualTo(2);
    }

    @Test
    void successfulButTruncatedResponseIsUncertain() {
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200,
                        "{\"choices\":[{\"message\":{\"content\":\"截断\"},\"finish_reason\":\"length\"}]}", 10));
        assertThatThrownBy(() -> client(transport).rewrite(
                new ScriptRewritePort.RewriteCall("原文", null, null, ProviderCallAudit.NONE)))
                .isInstanceOf(ContentProviderException.Uncertain.class)
                .hasMessageContaining("超过输出上限");
    }

    @Test
    void successfulButEmptyContentIsUncertain() {
        ContentProviderTransport.Scripted transport = new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200,
                        "{\"choices\":[{\"message\":{\"content\":\"  \"},\"finish_reason\":\"stop\"}]}", 10));
        assertThatThrownBy(() -> client(transport).rewrite(
                new ScriptRewritePort.RewriteCall("原文", null, null, ProviderCallAudit.NONE)))
                .isInstanceOf(ContentProviderException.Uncertain.class)
                .hasMessageContaining("结果为空");
    }

    @Test
    void verifiedNotAcceptedWhitelistCanUseFreeFallback() {
        for (int status : new int[]{400, 401, 403, 404, 422}) {
            assertThatThrownBy(() -> client(new ContentProviderTransport.Scripted(
                    ContentProviderTransport.json(status, "rejected", 10))).rewrite(
                    new ScriptRewritePort.RewriteCall("原文", null, null, ProviderCallAudit.NONE)))
                    .isInstanceOfAny(ContentProviderException.Rejection.class,
                            ContentProviderException.Unavailable.class);
        }
    }

    @Test
    void unverifiedClientErrorsAreUncertainAndMustNotRefund() {
        for (int status : new int[]{409, 418, 425, 451}) {
            assertThatThrownBy(() -> client(new ContentProviderTransport.Scripted(
                    ContentProviderTransport.json(status, "unknown", 10))).rewrite(
                    new ScriptRewritePort.RewriteCall("原文", null, null, ProviderCallAudit.NONE)))
                    .isInstanceOf(ContentProviderException.Uncertain.class);
        }
    }

    @Test
    void realLocalHttpKeepsOnlyVerifiedNotAcceptedStatusesRefundable() throws Exception {
        AtomicInteger status = new AtomicInteger(400);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] body = "provider-response".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ContentProviderProperties.DeepSeek local = config();
            local.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            DeepSeekChatClient client = new DeepSeekChatClient(local, new ContentProviderTransport.Jdk(), null);
            ScriptRewritePort.RewriteCall call = new ScriptRewritePort.RewriteCall(
                    "原文", null, null, ProviderCallAudit.NONE);

            assertThatThrownBy(() -> client.rewrite(call))
                    .isInstanceOf(ContentProviderException.Rejection.class);
            for (int unknown : new int[]{409, 425, 429, 500}) {
                status.set(unknown);
                assertThatThrownBy(() -> client.rewrite(call))
                        .isInstanceOf(ContentProviderException.Uncertain.class);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void timeoutRateLimitAndServerErrorsAreUncertain() {
        for (int status : new int[]{408, 429, 500, 503}) {
            assertThatThrownBy(() -> client(new ContentProviderTransport.Scripted(
                    ContentProviderTransport.json(status, "unknown", 10))).rewrite(
                    new ScriptRewritePort.RewriteCall("原文", null, null, ProviderCallAudit.NONE)))
                    .isInstanceOf(ContentProviderException.Uncertain.class);
        }
    }

    @Test
    void successfulButMalformedResponseIsUncertain() {
        for (String body : new String[]{"not-json", "{}", "{\"choices\":[]}",
                "{\"choices\":[{\"message\":{\"content\":\"有文字\"},\"finish_reason\":\"unknown\"}]}"}) {
            assertThatThrownBy(() -> client(new ContentProviderTransport.Scripted(
                    ContentProviderTransport.json(200, body, 10))).rewrite(
                    new ScriptRewritePort.RewriteCall("原文", null, null, ProviderCallAudit.NONE)))
                    .isInstanceOf(ContentProviderException.Uncertain.class);
        }
    }

    @Test
    void redirectWithoutDefinitiveAcceptanceIsUncertain() {
        assertThatThrownBy(() -> client(new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(302, "redirect", 10))).rewrite(
                new ScriptRewritePort.RewriteCall("原文", null, null, ProviderCallAudit.NONE)))
                .isInstanceOf(ContentProviderException.Uncertain.class);
    }

    @Test
    void credentialsAndNetworkKeepTheirExistingClassification() {

        assertThatThrownBy(() -> client(new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(401, "denied", 10))).rewrite(
                new ScriptRewritePort.RewriteCall("原文", null, null, ProviderCallAudit.NONE)))
                .isInstanceOf(ContentProviderException.Unavailable.class)
                .hasMessageContaining("凭据");

        assertThatThrownBy(() -> client(new ContentProviderTransport.Scripted(false)).rewrite(
                new ScriptRewritePort.RewriteCall("原文", null, null, ProviderCallAudit.NONE)))
                .isInstanceOf(ContentProviderException.Uncertain.class);
    }

    @Test
    void unconfiguredKeyIsUnavailable() {
        DeepSeekChatClient unconfigured = new DeepSeekChatClient(
                new ContentProviderProperties.DeepSeek(), new ContentProviderTransport.Scripted(), null);
        assertThatThrownBy(() -> unconfigured.rewrite(
                new ScriptRewritePort.RewriteCall("原文", null, null, ProviderCallAudit.NONE)))
                .isInstanceOf(ContentProviderException.Unavailable.class)
                .hasMessageContaining("文案改写服务尚未配置");
    }

}
