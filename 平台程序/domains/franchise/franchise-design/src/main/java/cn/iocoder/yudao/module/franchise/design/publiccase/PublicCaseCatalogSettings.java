package cn.iocoder.yudao.module.franchise.design.publiccase;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** Connection settings for A's anonymous, published-case catalog. */
public final class PublicCaseCatalogSettings {

    final URI origin;
    final String tenantId;
    final Duration connectTimeout;
    final Duration requestTimeout;
    final int maxResponseBytes;

    public PublicCaseCatalogSettings(URI origin, String tenantId, Duration connectTimeout,
                                     Duration requestTimeout, int maxResponseBytes) {
        this(origin, tenantId, connectTimeout, requestTimeout, maxResponseBytes, false);
    }

    private PublicCaseCatalogSettings(URI origin, String tenantId, Duration connectTimeout,
                                      Duration requestTimeout, int maxResponseBytes, boolean loopbackTest) {
        Objects.requireNonNull(origin, "origin");
        String host = origin.getHost();
        boolean loopback = "127.0.0.1".equals(host) || "::1".equals(host);
        if (host == null || origin.getUserInfo() != null || origin.getRawQuery() != null
                || origin.getRawFragment() != null || !(origin.getRawPath().isEmpty() || "/".equals(origin.getRawPath()))
                || (!"https".equals(origin.getScheme()) && !(loopbackTest && loopback && "http".equals(origin.getScheme())))
                || origin.getPort() == 0 || origin.getPort() > 65535) {
            throw new IllegalArgumentException("Public case origin must be a fixed HTTPS origin");
        }
        if (tenantId == null || !tenantId.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("Public case tenant-id must be a positive numeric identifier");
        }
        requireTimeout(connectTimeout);
        requireTimeout(requestTimeout);
        if (connectTimeout.compareTo(requestTimeout) > 0 || maxResponseBytes < 1
                || maxResponseBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid public case transport limits");
        }
        this.origin = origin;
        this.tenantId = tenantId;
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
        this.maxResponseBytes = maxResponseBytes;
    }

    static PublicCaseCatalogSettings forLoopbackTests(URI origin, String tenantId, Duration connectTimeout,
                                                      Duration requestTimeout, int maxResponseBytes) {
        return new PublicCaseCatalogSettings(origin, tenantId, connectTimeout, requestTimeout,
                maxResponseBytes, true);
    }

    private static void requireTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Public case timeout must be positive and at most five minutes");
        }
    }
}
