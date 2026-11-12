package cn.iocoder.yudao.module.franchise.design.remote.transport;

import cn.iocoder.yudao.module.franchise.api.capability.security.CapabilityKeyring;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

public final class CapabilityTransportSettings {
    final URI origin;
    final Duration connectTimeout;
    final Duration requestTimeout;
    final int maxRequestBytes;
    final int maxResponseBytes;
    final String keyId;
    private final byte[] secret;

    /** Production configuration always requires HTTPS and an origin without any resource path. */
    public CapabilityTransportSettings(URI origin, Duration connectTimeout, Duration requestTimeout,
                                       int maxRequestBytes, int maxResponseBytes, String keyId, byte[] secret) {
        this(origin, connectTimeout, requestTimeout, maxRequestBytes, maxResponseBytes, keyId, secret, false);
    }

    private CapabilityTransportSettings(URI origin, Duration connectTimeout, Duration requestTimeout,
                                        int maxRequestBytes, int maxResponseBytes, String keyId, byte[] secret,
                                        boolean loopbackTest) {
        Objects.requireNonNull(origin, "origin");
        String host = origin.getHost();
        boolean literalLoopback = "127.0.0.1".equals(host) || "[::1]".equals(host);
        if ((!"https".equals(origin.getScheme()) && !(loopbackTest && literalLoopback && "http".equals(origin.getScheme())))
                || host == null || origin.getUserInfo() != null || origin.getRawQuery() != null
                || origin.getRawFragment() != null || !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/"))
                || origin.getPort() == 0 || origin.getPort() > 65535) {
            throw new IllegalArgumentException("Capability origin must be a fixed HTTPS origin");
        }
        requireTimeout(connectTimeout);
        requireTimeout(requestTimeout);
        if (connectTimeout.compareTo(requestTimeout) > 0 || maxRequestBytes < 1 || maxRequestBytes > 4 * 1024 * 1024
                || maxResponseBytes < 1 || maxResponseBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid capability transport limits");
        }
        CapabilityKeyring.requireToken(keyId, 64);
        if (secret == null || secret.length < 32 || secret.length > 128) {
            throw new IllegalArgumentException("Capability key must contain 32 to 128 random bytes");
        }
        this.origin = origin;
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
        this.maxRequestBytes = maxRequestBytes;
        this.maxResponseBytes = maxResponseBytes;
        this.keyId = keyId;
        this.secret = secret.clone();
    }

    // Not exposed as a deploy-time property; tests may use literal loopback HTTP only.
    static CapabilityTransportSettings forLoopbackTests(URI origin, Duration connectTimeout, Duration requestTimeout,
                                                        int maxRequestBytes, int maxResponseBytes, String keyId, byte[] secret) {
        return new CapabilityTransportSettings(origin, connectTimeout, requestTimeout,
                maxRequestBytes, maxResponseBytes, keyId, secret, true);
    }

    byte[] secret() {
        return secret.clone();
    }

    private static void requireTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Capability timeout must be positive and at most five minutes");
        }
    }
}
