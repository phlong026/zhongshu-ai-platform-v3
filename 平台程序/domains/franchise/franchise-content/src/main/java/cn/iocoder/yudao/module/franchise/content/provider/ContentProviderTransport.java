package cn.iocoder.yudao.module.franchise.content.provider;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 可注入传输层（五件套之四）：所有 provider 外呼经此接口，便于单测注入假传输断言请求形状。
 * 真实实现用 JDK HttpClient：不跟随重定向（豆一豆/供应商端点均无重定向语义）、整请求超时、响应体限长。
 */
public interface ContentProviderTransport {

    Response exchange(String method, String url, Map<String, String> headers, byte[] body, int timeoutSeconds)
            throws IOException, InterruptedException;

    /** status + body；elapsedMs 供外呼日志（A2.5：每次外呼 provider/endpoint/耗时/结果码）。 */
    record Response(int status, byte[] body, long elapsedMs) {
    }

    /** 超时/网络类失败由调用方按 Uncertain 语义处理；HTTP 状态原样返回（含 4xx/5xx）。 */
    class NetworkFailure extends IOException {
        private final boolean timeout;

        public NetworkFailure(boolean timeout, Throwable cause) {
            super(timeout ? "timeout" : "network", cause);
            this.timeout = timeout;
        }

        public boolean isTimeout() {
            return timeout;
        }
    }

    /** 真实传输：JDK17 HttpClient，连接池由 JVM 管理；响应体最多读 maxBodyBytes（默认 4MB，防内存放大）。 */
    class Jdk implements ContentProviderTransport {

        public static final int DEFAULT_MAX_BODY_BYTES = 4 * 1024 * 1024;

        private final HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        private final int maxBodyBytes;

        public Jdk() {
            this(DEFAULT_MAX_BODY_BYTES);
        }

        public Jdk(int maxBodyBytes) {
            this.maxBodyBytes = maxBodyBytes;
        }

        @Override
        public Response exchange(String method, String url, Map<String, String> headers, byte[] body,
                                 int timeoutSeconds) throws IOException, InterruptedException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds));
            String upper = method.toUpperCase();
            if ("GET".equals(upper) || "HEAD".equals(upper)) {
                builder = builder.method(upper, HttpRequest.BodyPublishers.noBody());
            } else {
                builder = builder.method(upper, HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
            }
            for (Map.Entry<String, String> header : headers.entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }
            long start = System.nanoTime();
            HttpResponse<byte[]> response;
            try {
                response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            } catch (java.net.http.HttpTimeoutException e) {
                throw new NetworkFailure(true, e);
            } catch (ConnectException | java.net.UnknownHostException e) {
                throw new NetworkFailure(false, e);
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            byte[] payload = response.body();
            if (payload != null && payload.length > maxBodyBytes) {
                payload = java.util.Arrays.copyOf(payload, maxBodyBytes);
            }
            return new Response(response.statusCode(), payload == null ? new byte[0] : payload, elapsedMs);
        }
    }

    /** 假传输（单测断言请求形状）：按序回放脚本化响应。 */
    class Scripted implements ContentProviderTransport {
        public java.util.List<Call> calls = new java.util.ArrayList<>();
        private final java.util.Queue<Response> script;
        private final IOException failure;

        public record Call(String method, String url, Map<String, String> headers, byte[] body) {
        }

        public Scripted(Response... responses) {
            this.script = new java.util.ArrayDeque<>(java.util.Arrays.asList(responses));
            this.failure = null;
        }

        /** 恒定网络失败（Uncertain 语义单测） */
        public Scripted(boolean timeout) {
            this.script = new java.util.ArrayDeque<>();
            this.failure = new NetworkFailure(timeout, null);
        }

        @Override
        public Response exchange(String method, String url, Map<String, String> headers, byte[] body,
                                 int timeoutSeconds) throws IOException {
            calls.add(new Call(method, url, headers, body));
            if (failure != null) {
                throw failure;
            }
            Response next = script.poll();
            if (next == null) {
                throw new IllegalStateException("scripted transport exhausted");
            }
            return next;
        }
    }

    /** 便捷构造：JSON 响应 */
    static Response json(int status, String jsonBody, long elapsedMs) {
        return new Response(status, jsonBody.getBytes(java.nio.charset.StandardCharsets.UTF_8), elapsedMs);
    }

}
