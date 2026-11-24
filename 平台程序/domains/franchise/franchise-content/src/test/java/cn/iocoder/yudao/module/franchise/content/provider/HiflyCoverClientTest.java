package cn.iocoder.yudao.module.franchise.content.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class HiflyCoverClientTest {
    @Test void templatesAndCreationUseDocumentedEndpointsAndContentWithoutInventedSlots() throws Exception {
        var transport = new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200, "{\"code\":0,\"data\":[{\"id\":\"cs_123\",\"title\":\"商务红\",\"url\":\"https://images.example/template.jpg\",\"slots\":[]}],\"total\":1}", 0),
                ContentProviderTransport.json(200, "{\"code\":0,\"cover_id\":\"cover_123\",\"url\":\"https://images.example/cover.jpg?sig=fixture\"}", 0));
        var client = client(transport);
        var list = client.templates(2, 20, ProviderCallAudit.NONE);
        assertThat(list.total()).isEqualTo(1);
        assertThat(list.list()).extracting(HiflyCoverClient.Template::id).containsExactly("cs_123");
        assertThat(transport.calls.get(0).url()).endsWith("/api/v2/hifly/project/cover_styles?page=2&size=20");
        assertThat(client.create("cs_123", "乡墅案例", "3:4", ProviderCallAudit.NONE).id()).isEqualTo("cover_123");
        var sent = transport.calls.get(1);
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.url()).endsWith("/api/v2/hifly/project/covers");
        var payload = new ObjectMapper().readTree(sent.body());
        assertThat(payload.path("template").asText()).isEqualTo("cs_123");
        assertThat(payload.path("content").asText()).isEqualTo("乡墅案例");
        assertThat(payload.path("canvas_preset").asText()).isEqualTo("3:4");
        assertThat(payload.path("output_format").asText()).isEqualTo("jpg");
        assertThat(payload.has("slots")).isFalse();
    }

    @Test void missingOrInvalidCreationReceiptIsUncertainWhileExplicitRejectionsAreDefinitive() {
        for (String body : List.of("{}", "{\"code\":0}", "{\"code\":0,\"cover_id\":\"c\",\"url\":\"http://insecure.test/c.jpg\"}")) {
            var transport = new ContentProviderTransport.Scripted(ContentProviderTransport.json(200, body, 0));
            assertThatThrownBy(() -> client(transport).create("cs", "标题", "9:16", ProviderCallAudit.NONE))
                    .isInstanceOf(ContentProviderException.Uncertain.class);
            assertThat(transport.calls).hasSize(1);
        }
        var failed = new ContentProviderTransport.Scripted(true);
        assertThatThrownBy(() -> client(failed).create("cs", "标题", "9:16", ProviderCallAudit.NONE))
                .isInstanceOf(ContentProviderException.Uncertain.class);
        for (int status : List.of(401, 403)) {
            var transport = new ContentProviderTransport.Scripted(ContentProviderTransport.json(status, "{}", 0));
            assertThatThrownBy(() -> client(transport).create("cs", "标题", "9:16", ProviderCallAudit.NONE))
                    .isInstanceOf(ContentProviderException.Unavailable.class);
        }
        var rejected = new ContentProviderTransport.Scripted(ContentProviderTransport.json(200, "{\"code\":1013}", 0));
        assertThatThrownBy(() -> client(rejected).create("cs", "标题", "9:16", ProviderCallAudit.NONE))
                .isInstanceOf(ContentProviderException.Rejection.class);
    }

    private HiflyCoverClient client(ContentProviderTransport transport) {
        var config = new ContentProviderProperties.Hifly(); config.setApiKey("fixture-not-a-live-key");
        return new HiflyCoverClient(config, transport, null);
    }
}
