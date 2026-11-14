import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;

/** API-image -> Provider TLS/capability-HMAC probe; it has no business payload or persistence. */
public final class CapabilitySmoke {
    public static void main(String[] args) {
        try {
            if (args.length != 2) throw new IllegalArgumentException("SMOKE_ARGUMENTS");
            var properties = new Properties();
            try (var input = Files.newInputStream(Path.of(args[1]))) { properties.load(input); }
            var keyId = properties.getProperty("zhongshu.design.remote.active-key-id", "");
            var caller = properties.getProperty("zhongshu.design.remote.caller-app-id", "");
            var secret = Base64.getDecoder().decode(properties.getProperty("zhongshu.design.remote.keys." + keyId, ""));
            if (secret.length < 32 || caller.isBlank()) throw new IllegalArgumentException("SMOKE_IDENTITY");
            var path = "/internal-api/deploy-smoke/platform";
            var body = new byte[0];
            var timestamp = Long.toString(Instant.now().getEpochSecond());
            var bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            var signature = HexFormat.of().formatHex(mac.doFinal(
                    (timestamp + "\nPOST\n" + path + "\n" + bodyHash).getBytes(StandardCharsets.US_ASCII)));
            var request = HttpRequest.newBuilder(URI.create(args[0] + path)).timeout(Duration.ofSeconds(5))
                    .header("X-ZS-Timestamp", timestamp).header("X-ZS-Signature", signature)
                    .header("X-ZS-Key-Id", keyId).header("X-ZS-Caller-App-Id", caller)
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 204) throw new IllegalStateException("SMOKE_STATUS_" + response.statusCode());
        } catch (Exception error) {
            System.err.println("API-to-Provider capability smoke failed: " + error.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
