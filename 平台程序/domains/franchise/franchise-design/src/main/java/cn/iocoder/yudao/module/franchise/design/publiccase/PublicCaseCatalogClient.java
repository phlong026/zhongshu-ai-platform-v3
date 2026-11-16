package cn.iocoder.yudao.module.franchise.design.publiccase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLException;

/**
 * Reads only A's anonymous published-case endpoints. The returned JSON remains unchanged so B cannot
 * accidentally broaden A's {@code allowedActions} or infer private asset rights.
 */
public final class PublicCaseCatalogClient {

    private static final Logger log = LoggerFactory.getLogger(PublicCaseCatalogClient.class);
    private static final String CASES_PATH = "/app-api/design/v1/cases";
    private static final Set<String> LIST_PARAMETERS = Set.of(
            "sourceType", "styleCode", "floorCount", "minArea", "maxArea", "faceWidth", "depth",
            "keyword", "cursor", "limit");

    private final PublicCaseCatalogSettings settings;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public PublicCaseCatalogClient(PublicCaseCatalogSettings settings, ObjectMapper mapper) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.client = HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public JsonNode listCases(Map<String, String> parameters) {
        Objects.requireNonNull(parameters, "parameters");
        var query = new ArrayList<String>();
        parameters.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.naturalOrder())).forEach(entry -> {
            if (!LIST_PARAMETERS.contains(entry.getKey()) || entry.getValue() == null) {
                throw new IllegalArgumentException("Unsupported public case list parameter");
            }
            query.add(encode(entry.getKey()) + "=" + encode(entry.getValue()));
        });
        return get(Endpoint.LIST, CASES_PATH + (query.isEmpty() ? "" : "?" + String.join("&", query)));
    }

    public JsonNode listStyles() {
        return get(Endpoint.STYLES, CASES_PATH + "/styles");
    }
    public JsonNode generation(String path,Map<String,String> parameters) {
        if(!path.equals("/cases/generation-candidates")&&!path.matches("/cases/[1-9][0-9]*/generation-candidate")&&!path.equals("/cases/recommendation"))throw new IllegalArgumentException("Invalid generation catalog route");
        Set<String> allowed=Set.of("floorCount","faceWidth","depth","dimensionBasis","caseVersionId","area");
        var query=new ArrayList<String>();parameters.forEach((key,value)->{if(!allowed.contains(key))throw new IllegalArgumentException("Invalid generation query");query.add(encode(key)+"="+encode(value));});
        String sourcePath=path.replace("/generation-candidates","/public-generation-candidates").replace("/generation-candidate","/public-generation-candidate");
        return get(Endpoint.DETAIL,"/app-api/design/v1"+sourcePath+(query.isEmpty()?"":"?"+String.join("&",query)));
    }

    public JsonNode getCase(long caseId) {
        return getCase(caseId, settings.requestTimeout);
    }

    public JsonNode getCase(long caseId, Duration remaining) {
        if (caseId <= 0) {
            throw new IllegalArgumentException("caseId must be positive");
        }
        if (remaining == null || remaining.isZero() || remaining.isNegative()) {
            throw new IllegalArgumentException("remaining deadline must be positive");
        }
        Duration deadline = remaining.compareTo(settings.requestTimeout) < 0 ? remaining : settings.requestTimeout;
        return get(Endpoint.DETAIL, CASES_PATH + "/" + caseId, deadline);
    }

    /** 匿名公开图片链：必须先从当前已发布案例详情确认图片归属，再换一次性票据。 */
    public JsonNode caseAssetDownloadUrl(long caseId, long assetId) {
        if (assetId <= 0) throw new IllegalArgumentException("assetId must be positive");
        long started = System.nanoTime();
        JsonNode detail = getCase(caseId);
        String id = String.valueOf(assetId);
        boolean member = id.equals(detail.path("coverAssetId").asText());
        member |= id.equals(detail.path("elevationAssetId").asText());
        for (JsonNode image : detail.path("floorPlanAssetIds")) member |= id.equals(image.asText());
        for (JsonNode image : detail.path("floorPlans")) member |= id.equals(image.path("assetId").asText());
        if (!String.valueOf(caseId).equals(detail.path("caseId").asText()) || !member) {
            throw new PublicCaseCatalogException("图片不属于当前公开案例",403,false,false);
        }
        String path = "/app-api/design/v1/assets/" + assetId;
        JsonNode ticket = exchange(Endpoint.DOWNLOAD,path+"/download-tickets",remaining(started),"{}");
        String token = ticket.path("ticketId").asText("");
        if (token.isBlank() || token.length()>256) throw new PublicCaseCatalogException("公开图片票据无效",null,false,true);
        JsonNode result = exchange(Endpoint.DOWNLOAD,path+"/downloads",remaining(started),
                mapper.createObjectNode().put("ticket",token).toString());
        try {
            java.net.URI url = java.net.URI.create(result.path("downloadUrl").asText(""));
            if (!"https".equals(url.getScheme()) || url.getHost()==null || url.getRawUserInfo()!=null)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new PublicCaseCatalogException("公开图片下载地址无效",null,false,true);
        }
        return result;
    }

    private Duration remaining(long started) {
        Duration remaining = settings.requestTimeout.minusNanos(System.nanoTime()-started);
        if (remaining.isNegative() || remaining.isZero()) throw new PublicCaseCatalogException("公开图片请求超时",null,true,false);
        return remaining;
    }

    private JsonNode get(Endpoint endpoint, String pathAndQuery) {
        return get(endpoint, pathAndQuery, settings.requestTimeout);
    }

    private JsonNode get(Endpoint endpoint, String pathAndQuery, Duration deadline) {
        return exchange(endpoint,pathAndQuery,deadline,null);
    }

    private JsonNode exchange(Endpoint endpoint,String pathAndQuery,Duration deadline,String bodyJson) {
        var builder = HttpRequest.newBuilder(settings.origin.resolve(pathAndQuery))
                .timeout(deadline)
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity")
                .header("tenant-id", settings.tenantId);
        HttpRequest request = bodyJson == null ? builder.GET().build() : builder.header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson,StandardCharsets.UTF_8)).build();
        BoundedBodySubscriber subscriber = new BoundedBodySubscriber(settings.maxResponseBytes);
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        AtomicInteger upstreamStatus = new AtomicInteger(-1);
        long started = System.nanoTime();
        try {
            pending = client.sendAsync(request, info -> {
                upstreamStatus.set(info.statusCode());
                if (info.headers().firstValueAsLong("Content-Length").orElse(0) > settings.maxResponseBytes) {
                    subscriber.cancelTooLarge();
                } else if (!info.headers().firstValue("Content-Encoding").orElse("identity")
                        .equalsIgnoreCase("identity")) {
                    subscriber.cancel(new IOException("Encoded public case response rejected"));
                }
                return subscriber;
            });
            long remaining = deadline.toNanos() - (System.nanoTime() - started);
            if (remaining <= 0) {
                throw new TimeoutException("Public case response deadline exceeded");
            }
            // HttpRequest.timeout alone does not bound consumption after response headers arrive.
            HttpResponse<byte[]> response = pending.get(remaining, TimeUnit.NANOSECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw failure(endpoint, FailureKind.HTTP_STATUS, response.statusCode(), null, started,
                        "公开案例服务暂不可用");
            }
            byte[] body = response.body();
            JsonNode envelope;
            try {
                envelope = mapper.readTree(body);
            } catch (JsonProcessingException invalidJson) {
                throw failure(endpoint, FailureKind.INVALID_RESPONSE, response.statusCode(), null, started,
                        "公开案例服务返回无效响应");
            }
            if (envelope == null || !envelope.isObject()) {
                throw failure(endpoint, FailureKind.INVALID_RESPONSE, response.statusCode(), null, started,
                        "公开案例服务返回无效响应");
            }
            JsonNode code = envelope.path("code");
            if (!code.canConvertToInt()) {
                throw failure(endpoint, FailureKind.INVALID_RESPONSE, response.statusCode(), null, started,
                        "公开案例服务返回无效响应");
            }
            if (code.intValue() != 0) {
                throw failure(endpoint, FailureKind.UPSTREAM_BUSINESS, response.statusCode(), code.intValue(), started,
                        "公开案例服务拒绝请求");
            }
            JsonNode data = envelope.get("data");
            if (data == null || data.isNull()) {
                throw failure(endpoint, FailureKind.INVALID_RESPONSE, response.statusCode(), null, started,
                        "公开案例服务返回无效响应");
            }
            return data;
        } catch (PublicCaseCatalogException expected) {
            throw expected;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            subscriber.cancel(interrupted);
            if (pending != null) {
                pending.cancel(true);
            }
            throw failure(endpoint, FailureKind.INTERRUPTED, upstreamStatus.get(), null, started,
                    "公开案例服务暂不可用");
        } catch (ExecutionException | TimeoutException | IOException | RuntimeException failure) {
            subscriber.cancel(failure);
            if (pending != null) {
                pending.cancel(true);
            }
            if (subscriber.exceededLimit()) {
                throw failure(endpoint, FailureKind.RESPONSE_TOO_LARGE, upstreamStatus.get(), null, started,
                        "公开案例服务响应过大");
            }
            boolean timedOut = isTimeout(failure);
            FailureKind kind = timedOut ? FailureKind.TIMEOUT : FailureKind.TRANSPORT;
            TransportCause cause = timedOut ? TransportCause.TIMEOUT : transportCause(failure);
            throw failure(endpoint, kind, upstreamStatus.get(), null, started, cause,
                    "公开案例服务暂不可用");
        }
    }

    private static boolean isTimeout(Throwable failure) {
        return failure instanceof TimeoutException
                || failure instanceof ExecutionException && failure.getCause() instanceof HttpTimeoutException;
    }

    static TransportCause transportCause(Throwable failure) {
        Throwable current = failure;
        boolean sawIo = false;
        for (int depth = 0; current != null && depth < 12; depth++, current = current.getCause()) {
            if (current instanceof UnknownHostException) {
                return TransportCause.DNS;
            }
            if (current instanceof SSLException) {
                return TransportCause.TLS;
            }
            if (current instanceof ConnectException) {
                return TransportCause.CONNECT;
            }
            if (current instanceof SocketException || current instanceof EOFException) {
                return TransportCause.RESET;
            }
            if (current instanceof IOException) {
                sawIo = true;
            }
        }
        return sawIo ? TransportCause.IO : TransportCause.OTHER;
    }

    private static PublicCaseCatalogException failure(Endpoint endpoint, FailureKind kind, int status,
                                                      Integer upstreamCode, long started, String clientMessage) {
        return failure(endpoint, kind, status, upstreamCode, started, TransportCause.NONE, clientMessage);
    }

    private static PublicCaseCatalogException failure(Endpoint endpoint, FailureKind kind, int status,
                                                      Integer upstreamCode, long started, TransportCause cause,
                                                      String clientMessage) {
        long elapsedMs = Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        log.warn("[publicCaseCatalog] endpoint={} failureKind={} httpStatus={} upstreamCode={} elapsedMs={} transportCause={}",
                endpoint, kind, status, upstreamCode, elapsedMs, cause);
        return new PublicCaseCatalogException(clientMessage, upstreamCode, kind == FailureKind.TIMEOUT,
                kind == FailureKind.INVALID_RESPONSE || kind == FailureKind.RESPONSE_TOO_LARGE);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private enum Endpoint { LIST, STYLES, DETAIL, DOWNLOAD }

    private enum FailureKind {
        HTTP_STATUS, UPSTREAM_BUSINESS, INVALID_RESPONSE, RESPONSE_TOO_LARGE, TIMEOUT, INTERRUPTED, TRANSPORT
    }

    enum TransportCause { NONE, DNS, TLS, CONNECT, RESET, IO, TIMEOUT, OTHER }
}
