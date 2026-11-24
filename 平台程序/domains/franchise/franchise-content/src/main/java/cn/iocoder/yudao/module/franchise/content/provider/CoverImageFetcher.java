package cn.iocoder.yudao.module.franchise.content.provider;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Only consumes a persisted provider receipt; no client-supplied URL or credentials are accepted. */
@Component
public class CoverImageFetcher {
    private static final int MAX_BYTES = 10 << 20;
    // DNS is checked at connection time, not by a separate lookup vulnerable to rebinding.
    private final OkHttpClient client;
    public CoverImageFetcher() {
        this(new OkHttpClient.Builder().dns(CoverImageFetcher::publicAddresses)
                .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
                .callTimeout(Duration.ofSeconds(45)).connectTimeout(Duration.ofSeconds(10)).build());
    }
    CoverImageFetcher(OkHttpClient client) { this.client = client; }
    public record Image(byte[] bytes, String mimeType) { }

    public Image fetch(String url) {
        if (!safeUri(url)) throw unavailable();
        try (var response = client.newCall(new Request.Builder().url(url).get().build()).execute()) {
            if (response.code() != 200 || response.body() == null) throw unavailable();
            String type = response.header("Content-Type", "").split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
            if (!Set.of("image/png", "image/jpeg").contains(type) || response.body().contentLength() > MAX_BYTES)
                throw unavailable();
            byte[] bytes = response.body().byteStream().readNBytes(MAX_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_BYTES) throw unavailable();
            return new Image(bytes, type);
        } catch (IOException error) { throw unavailable(); }
    }

    static boolean safeUri(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            return value.length() <= 4096 && "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    // OkHttp bypasses custom DNS for literal addresses, so do not accept literals at all.
                    && host.indexOf(':') < 0 && !host.matches("[0-9.]+")
                    && uri.getRawUserInfo() == null && uri.getRawFragment() == null && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (RuntimeException error) { return false; }
    }

    static List<InetAddress> publicAddresses(String hostname) throws UnknownHostException {
        List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
        if (addresses.isEmpty() || addresses.stream().anyMatch(address -> !isPublic(address)))
            throw new UnknownHostException("封面图片来源不可访问");
        return addresses;
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 255, second = bytes[1] & 255;
        if (bytes.length == 4) return first != 0 && first < 224 && first != 127
                && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 169 && second == 254) && !(first == 198 && (second == 18 || second == 19))
                && !(first == 192 && second == 0 && ((bytes[2] & 255) == 0 || (bytes[2] & 255) == 2))
                && !(first == 198 && second == 51 && (bytes[2] & 255) == 100)
                && !(first == 203 && second == 0 && (bytes[2] & 255) == 113);
        // Global IPv6 unicast only. Excludes ULA, mapped, transition and documentation addresses.
        return (first & 0xe0) == 0x20 && !(first == 0x20 && second == 0x02)
                && !(first == 0x20 && second == 0x01 && ((bytes[2] & 255) < 2
                || ((bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8)));
    }

    private static ContentProviderException.Unavailable unavailable() {
        return new ContentProviderException.Unavailable("封面图片保存暂未完成，请继续核对");
    }
}
