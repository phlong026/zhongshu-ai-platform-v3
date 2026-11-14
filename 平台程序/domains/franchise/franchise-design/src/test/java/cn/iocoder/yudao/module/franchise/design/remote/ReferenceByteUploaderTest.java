package cn.iocoder.yudao.module.franchise.design.remote;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

class ReferenceByteUploaderTest {
    @Test
    void exactBytesUseOnlyContentTypeAndRedirectsAreNotFollowed() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        var followed = new AtomicInteger();
        server.createContext(
                "/ok",
                e -> {
                    assertThat(e.getRequestMethod()).isEqualTo("PUT");
                    assertThat(e.getRequestBody().readAllBytes()).containsExactly(1, 2, 3);
                    assertThat(e.getRequestHeaders().getFirst("Authorization")).isNull();
                    assertThat(e.getRequestHeaders().getFirst("X-Post-Id")).isNull();
                    assertThat(e.getRequestHeaders().getFirst("tenant-id")).isNull();
                    calls.incrementAndGet();
                    e.sendResponseHeaders(204, -1);
                    e.close();
                });
        server.createContext(
                "/redirect",
                e -> {
                    e.getResponseHeaders().add("Location", "/target");
                    e.sendResponseHeaders(307, -1);
                    e.close();
                });
        server.createContext(
                "/target",
                e -> {
                    followed.incrementAndGet();
                    e.sendResponseHeaders(204, -1);
                    e.close();
                });
        server.start();
        try {
            var uploader = new ReferenceByteUploader(Set.of("127.0.0.1"), true);
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            uploader.put(base + "/ok", new byte[] {1, 2, 3}, "image/png");
            assertThat(calls.get()).isEqualTo(1);
            assertThatThrownBy(() -> uploader.put(base + "/redirect", new byte[] {1}, "image/png"))
                    .hasMessageContaining("传输失败");
            assertThat(followed.get()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void productionRejectsUntrustedOrCredentialBearingUrlsBeforeNetwork() {
        var uploader = new ReferenceByteUploader(Set.of("objects.example"));
        for (String url :
                java.util.List.of(
                        "http://objects.example/ref",
                        "https://objects.example.evil/ref",
                        "https://u:p@objects.example/ref",
                        "https://objects.example/ref#secret",
                        "https://objects.example:444/ref",
                        "/admin-api/platform/dev/design/assets/1"))
            assertThatThrownBy(() -> uploader.put(url, new byte[] {1}, "image/png"))
                    .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new ReferenceByteUploader(Set.of()).requireReady())
                .hasMessageContaining("未配置");
    }
}
