package cn.iocoder.yudao.module.franchise.api.capability.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** Wire signature only. Its body hash is NOT a business idempotency fingerprint. */
public final class CapabilitySignature {
    public static final String TIMESTAMP_HEADER = "X-ZS-Timestamp";
    public static final String SIGNATURE_HEADER = "X-ZS-Signature";
    public static final String KEY_ID_HEADER = "X-ZS-Key-Id";

    private CapabilitySignature() {
    }

    /** The caller serializes once; these same bytes must be sent without re-encoding. */
    public static String sign(byte[] secret, String timestamp, String method, String rawPath, byte[] rawBody) {
        Objects.requireNonNull(rawBody, "rawBody");
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBody));
            String payload = timestamp + "\n" + method.toUpperCase(Locale.ROOT) + "\n" + rawPath + "\n" + hash;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Capability signature calculation failed", e);
        }
    }

    public static boolean verify(byte[] secret, String timestamp, String method, String rawPath,
                                 byte[] rawBody, String signature) {
        if (timestamp == null || signature == null || signature.length() != 64) {
            return false;
        }
        // Preserve the existing lower-case hex wire format and constant-time comparison.
        return MessageDigest.isEqual(sign(secret, timestamp, method, rawPath, rawBody)
                .getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII));
    }
}
