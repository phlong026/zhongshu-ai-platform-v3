import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;

/** Provider -> Runtime signed, read-only receipt probe. Never prints secret material or response bodies. */
public final class HmacSmoke {
    public static void main(String[] args) {
        try {
            if (args.length != 2) throw new IllegalArgumentException("SMOKE_ARGUMENTS");
            var properties = new Properties();
            try (var input = Files.newInputStream(Path.of(args[1]))) { properties.load(input); }
            var secret = properties.getProperty("zhongshu.ai.internal-secret", "");
            if (secret.length() < 32) throw new IllegalArgumentException("SMOKE_SECRET");
            var path = "/internal/note-polish/receipt";
            var body = ("{\"receiptId\":\"deploy-smoke-" + UUID.randomUUID() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            var timestamp = Long.toString(Instant.now().getEpochSecond());
            var bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            var payload = timestamp + "\nPOST\n" + path + "\n" + bodyHash;
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var signature = HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
            var request = HttpRequest.newBuilder(URI.create(args[0] + path)).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json").header("X-ZS-Timestamp", timestamp)
                    .header("X-ZS-Signature", signature).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) throw new IllegalStateException("SMOKE_STATUS_" + response.statusCode());
        } catch (Exception error) {
            System.err.println("Provider-to-Runtime HMAC smoke failed: " + error.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
