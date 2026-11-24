package cn.iocoder.yudao.module.franchise.content.provider;

import okhttp3.*;
import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class CoverImageFetcherTest {
    @Test void connectionDnsRejectsPrivateSpecialAndMappedAddresses() throws Exception {
        for (String ip : List.of("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.0.1", "169.254.169.254",
                "0.0.0.0", "100.64.0.1", "198.18.0.1", "192.0.2.1", "224.0.0.1", "240.0.0.1",
                "::1", "fc00::1", "fe80::1", "::ffff:127.0.0.1", "2001:db8::1", "2002:7f00:1::1", "2001::1")) {
            assertThat(CoverImageFetcher.isPublic(InetAddress.getByName(ip))).as(ip).isFalse();
        }
        for (String ip : List.of("8.8.8.8", "1.1.1.1", "2606:4700::1111", "2001:4860:4860::8888"))
            assertThat(CoverImageFetcher.isPublic(InetAddress.getByName(ip))).as(ip).isTrue();
        assertThatThrownBy(() -> CoverImageFetcher.publicAddresses("127.0.0.1")).isInstanceOf(java.net.UnknownHostException.class);
        for (String uri : List.of("file:///etc/passwd", "http://image.example/a.jpg", "https://user:pass@image.example/a.jpg", "https://image.example:8443/a.jpg"))
            assertThat(CoverImageFetcher.safeUri(uri)).isFalse();
    }

    @Test void literalHostsCannotBypassDnsFilteringOnTheActualFetchPath() {
        var seen = new AtomicReference<Request>();
        var fetcher = fetcher(200, "image/jpeg", new byte[]{1}, seen);
        for (String host : List.of("127.0.0.1", "127.1", "2130706433", "169.254.169.254", "[::1]", "[::ffff:127.0.0.1]")) {
            assertThatThrownBy(() -> fetcher.fetch("https://" + host + "/c.jpg"))
                    .as(host).isInstanceOf(ContentProviderException.Unavailable.class);
        }
        assertThat(seen.get()).isNull();
    }

    @Test void boundedImageDownloadDoesNotForwardApiCredentialsOrFollowRedirects() {
        var seen = new AtomicReference<Request>();
        var fetcher = fetcher(200, "image/jpeg", new byte[]{1, 2, 3}, seen);
        assertThat(fetcher.fetch("https://image.example/cover.jpg?sig=fixture").bytes()).containsExactly(1, 2, 3);
        assertThat(seen.get().header("Authorization")).isNull();
        assertThat(seen.get().url().query()).isEqualTo("sig=fixture");
        for (int status : List.of(302, 404, 500))
            assertThatThrownBy(() -> fetcher(status, "image/jpeg", new byte[]{1}, seen).fetch("https://image.example/c"))
                    .isInstanceOf(ContentProviderException.Unavailable.class);
        assertThatThrownBy(() -> fetcher(200, "text/html", new byte[]{1}, seen).fetch("https://image.example/c"))
                .isInstanceOf(ContentProviderException.Unavailable.class);
        assertThatThrownBy(() -> fetcher(200, "image/jpeg", new byte[(10 << 20) + 1], seen).fetch("https://image.example/c"))
                .isInstanceOf(ContentProviderException.Unavailable.class);
    }

    private CoverImageFetcher fetcher(int status, String mime, byte[] bytes, AtomicReference<Request> seen) {
        var client = new OkHttpClient.Builder().addInterceptor(chain -> {
            seen.set(chain.request());
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                    .header("Content-Type", mime).body(ResponseBody.create(bytes, MediaType.get(mime))).build();
        }).build();
        return new CoverImageFetcher(client);
    }
}
