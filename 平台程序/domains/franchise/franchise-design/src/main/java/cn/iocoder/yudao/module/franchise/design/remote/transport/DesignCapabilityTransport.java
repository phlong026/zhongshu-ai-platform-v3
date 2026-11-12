package cn.iocoder.yudao.module.franchise.design.remote.transport;

import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Operation;
import cn.iocoder.yudao.module.franchise.api.capability.security.CapabilitySignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Fixed-origin, single-attempt POST transport. Owns wire signing, never business fingerprints. */
public final class DesignCapabilityTransport {
    private static final Logger log = LoggerFactory.getLogger(DesignCapabilityTransport.class);
    private final CapabilityTransportSettings settings;
    private final HttpClient client;

    public DesignCapabilityTransport(CapabilityTransportSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        client = HttpClient.newBuilder().connectTimeout(settings.connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    }

    /**
     * Serialize the complete typed envelope exactly once before calling. The supplied bytes are cloned,
     * signed and sent unchanged. For retries the business lane retains its own stable payload bytes and
     * fingerprint; a new transport nonce is not a new business command.
     */
    public Response exchange(Operation operation, byte[] requestBody) {
        Objects.requireNonNull(operation, "operation");
        if (requestBody == null || requestBody.length == 0 || requestBody.length > settings.maxRequestBytes) {
            throw failure(operation, "Invalid capability request size", false, null);
        }
        byte[] body = requestBody.clone();
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        HttpRequest request = HttpRequest.newBuilder(settings.origin.resolve(operation.path()))
                .timeout(settings.requestTimeout).header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json").header("Accept-Encoding", "identity")
                .header(CapabilitySignature.KEY_ID_HEADER, settings.keyId)
                .header(CapabilitySignature.TIMESTAMP_HEADER, timestamp)
                .header(CapabilitySignature.SIGNATURE_HEADER,
                        CapabilitySignature.sign(settings.secret(), timestamp, "POST", operation.path(), body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        BoundedBodySubscriber subscriber = new BoundedBodySubscriber(settings.maxResponseBytes);
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        long started = System.nanoTime();
        // Transport has no business payload interpretation; mutating operations are conservative here.
        boolean unknownOnFailure = operation.mutating();
        try {
            pending = client.sendAsync(request, info -> {
                if (info.headers().firstValueAsLong("Content-Length").orElse(0) > settings.maxResponseBytes
                        || !info.headers().firstValue("Content-Encoding").orElse("identity").equalsIgnoreCase("identity")) {
                    subscriber.cancel(new IOException("Capability response length or encoding rejected"));
                }
                return subscriber;
            });
            long remaining = settings.requestTimeout.toNanos() - (System.nanoTime() - started);
            if (remaining <= 0) {
                throw new TimeoutException("Capability response deadline exceeded");
            }
            // HttpRequest.timeout alone does not bound consumption after headers arrive.
            HttpResponse<byte[]> response = pending.get(remaining, TimeUnit.NANOSECONDS);
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                throw new IOException("Capability redirects are prohibited");
            }
            return new Response(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            subscriber.cancel(e);
            if (pending != null) {
                pending.cancel(true);
            }
            throw failure(operation, "Capability exchange interrupted", unknownOnFailure, e);
        } catch (ExecutionException | TimeoutException | IOException | RuntimeException e) {
            subscriber.cancel(e);
            if (pending != null) {
                pending.cancel(true);
            }
            throw failure(operation, "Capability exchange failed", unknownOnFailure, e);
        }
    }

    private static CapabilityTransportException failure(Operation operation, String message, boolean unknown, Throwable cause) {
        log.warn("[capabilityTransport] operation={} failure={} outcomeUnknown={}", operation, message, unknown, cause);
        return new CapabilityTransportException(message, unknown, cause);
    }

    public record Response(int statusCode, byte[] responseBody) {
        public Response {
            responseBody = responseBody.clone();
        }

        @Override
        public byte[] responseBody() {
            return responseBody.clone();
        }
    }
}
