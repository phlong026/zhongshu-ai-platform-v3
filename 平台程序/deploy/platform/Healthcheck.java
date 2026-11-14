import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** JDK-only probe: credentials and response bodies never enter Docker health logs. */
public final class Healthcheck {
    public static void main(String[] args) {
        try {
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
            var url = System.getenv().getOrDefault("HEALTH_URL", "http://127.0.0.1:48080/actuator/health/readiness");
            var request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200
                    || !response.body().replaceAll("\\s", "").equals("{\"status\":\"UP\"}")) {
                System.err.println("Readiness probe failed");
                System.exit(1);
            }
        } catch (Exception error) {
            System.err.println("Readiness probe unavailable: " + error.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
