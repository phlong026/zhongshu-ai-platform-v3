package cn.iocoder.yudao.module.design.capability.security;

import cn.iocoder.yudao.framework.signature.core.redis.ApiSignatureRedisDAO;
import cn.iocoder.yudao.module.design.capability.CapabilityException;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Operation;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Request;
import cn.iocoder.yudao.module.franchise.api.capability.security.CapabilityKeyring;
import cn.iocoder.yudao.module.franchise.api.capability.security.CapabilitySignature;
import cn.iocoder.yudao.module.franchise.api.capability.security.ReplayWindow;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Performs all untrusted parsing before any existing unbounded body-cache filter. */
public final class CapabilityRequestAuthenticator {
    private static final Logger log = LoggerFactory.getLogger(CapabilityRequestAuthenticator.class);
    private static final Set<String> ENVELOPE_FIELDS = Set.of("callerAppId", "orgId", "actor", "operation", "requestKey", "nonce", "payload");
    private static final Set<String> IDENTITY_HEADERS = Set.of("authorization", "proxy-authorization", "tenant-id",
            "visit-tenant-id", "x-post-id", "x-user-id", "x-user-type", "x-tenant-id", "x-service-account-id", "cookie");
    private final CapabilityKeyring keyring;
    private final ReplayWindow window;
    private final Clock clock;
    private final ApiSignatureRedisDAO nonceDao;
    private final CallerOrganizationResolver resolver;
    private final int maximumBodyBytes;
    private final ObjectMapper mapper;

    public CapabilityRequestAuthenticator(CapabilityKeyring keyring, ReplayWindow window, Clock clock,
                                          ApiSignatureRedisDAO nonceDao, CallerOrganizationResolver resolver,
                                          int maximumBodyBytes) {
        this.keyring = Objects.requireNonNull(keyring, "keyring");
        this.window = Objects.requireNonNull(window, "window");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nonceDao = Objects.requireNonNull(nonceDao, "nonceDao");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        if (maximumBodyBytes < 1 || maximumBodyBytes > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("Capability body limit must be 1..4194304");
        }
        this.maximumBodyBytes = maximumBodyBytes;
        mapper = JsonMapper.builder(JsonFactory.builder()
                        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                                .maxNumberLength(100).maxStringLength(maximumBodyBytes).build()).build())
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    public Verified authenticate(HttpServletRequest request) {
        validateTransport(request);
        String keyId = singleHeader(request, CapabilitySignature.KEY_ID_HEADER);
        String timestamp = singleHeader(request, CapabilitySignature.TIMESTAMP_HEADER);
        String signature = singleHeader(request, CapabilitySignature.SIGNATURE_HEADER);
        if (!window.accepts(timestamp, clock.instant().getEpochSecond())) {
            throw denied(401, "Timestamp rejected");
        }
        byte[] rawBody = readBoundedBody(request);
        Request<?> envelope = parseEnvelope(rawBody, request.getRequestURI());
        // Reading a slow upload must not extend the signed timestamp's acceptance window.
        if (!window.accepts(timestamp, clock.instant().getEpochSecond())) {
            throw denied(401, "Timestamp expired while reading body");
        }
        final byte[] key;
        try {
            key = keyring.require(keyId, envelope.callerAppId(), envelope.operation().name());
        } catch (SecurityException e) {
            throw denied(401, "Service key denied");
        }
        if (!CapabilitySignature.verify(key, timestamp, "POST", request.getRequestURI(), rawBody, signature)) {
            throw denied(401, "Signature rejected");
        }
        final Boolean claimed;
        try {
            // Scope is caller, never keyId: replaying through a rotated key must still collide.
            claimed = nonceDao.setNonce("design-capability:" + envelope.callerAppId(), envelope.nonce(),
                    window.nonceTtlSeconds(), TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            log.error("[capabilityAuth] nonce storage unavailable caller={} operation={}",
                    envelope.callerAppId(), envelope.operation(), e);
            throw denied(503, "Nonce storage unavailable");
        }
        if (!Boolean.TRUE.equals(claimed)) {
            throw denied(claimed == null ? 503 : 409, claimed == null ? "Nonce storage indeterminate" : "Nonce replay rejected");
        }
        final ServicePrincipal principal;
        try {
            principal = resolver.resolveRequest(envelope);
        } catch (SecurityException e) {
            throw denied(403, "Caller organization denied");
        } catch (RuntimeException e) {
            if (e instanceof CapabilityException denial && denial.getHttpStatus() == 403 && denial.getCode() == 403) {
                throw denied(403, "Caller organization denied");
            }
            log.error("[capabilityAuth] mapping failed caller={} org={} operation={}",
                    envelope.callerAppId(), envelope.orgId(), envelope.operation(), e);
            throw denied(503, "Caller organization mapping unavailable");
        }
        if (principal == null || !principal.callerAppId().equals(envelope.callerAppId()) || principal.orgId() != envelope.orgId()) {
            throw denied(403, "Caller organization mapping rejected");
        }
        return new Verified(principal, envelope, rawBody);
    }

    static boolean isCapabilityRequest(HttpServletRequest request) {
        // servletPath also catches a container-decoded spelling; canonical raw paths are checked below.
        String root = "/internal-api/design/v1/platform";
        return inNamespace(request.getRequestURI(), request.getContextPath() + root)
                || inNamespace(request.getServletPath(), root);
    }

    private static boolean inNamespace(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    private static void validateTransport(HttpServletRequest request) {
        if (request.getDispatcherType() != DispatcherType.REQUEST || !request.getContextPath().isEmpty()
                || !"POST".equals(request.getMethod()) || request.getQueryString() != null) {
            throw denied(400, "Only direct query-free POST is supported");
        }
        for (String header : Collections.list(request.getHeaderNames())) {
            if (IDENTITY_HEADERS.contains(header.toLowerCase(java.util.Locale.ROOT))) {
                throw denied(400, "External identity header prohibited");
            }
        }
        if (request.getHeader("Content-Encoding") != null) {
            throw denied(415, "Encoded request bodies are prohibited");
        }
        try {
            MediaType type = MediaType.parseMediaType(request.getContentType());
            if (!"application".equals(type.getType()) || !"json".equals(type.getSubtype())
                    || (type.getCharset() != null && !StandardCharsets.UTF_8.equals(type.getCharset()))) {
                throw denied(415, "UTF-8 JSON required");
            }
        } catch (IllegalArgumentException e) {
            throw denied(415, "UTF-8 JSON required");
        }
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        if (values.size() != 1 || values.get(0).isEmpty() || values.get(0).length() > 128) {
            throw denied(401, "Missing or repeated signature header");
        }
        return values.get(0);
    }

    private byte[] readBoundedBody(HttpServletRequest request) {
        long declared = request.getContentLengthLong();
        if (declared > maximumBodyBytes) {
            throw denied(413, "Request exceeds byte limit");
        }
        try {
            byte[] body = request.getInputStream().readNBytes(maximumBodyBytes + 1);
            if (body.length > maximumBodyBytes) {
                throw denied(413, "Request exceeds byte limit");
            }
            if (body.length == 0 || (declared >= 0 && declared != body.length)) {
                throw denied(400, "Truncated request body");
            }
            return body;
        } catch (IOException e) {
            log.warn("[capabilityAuth] request body read failed path={}", request.getRequestURI(), e);
            throw denied(400, "Request body unavailable");
        }
    }

    private Request<?> parseEnvelope(byte[] bytes, String rawPath) {
        try {
            String json = decodeUtf8(bytes);
            JsonNode tree = mapper.readTree(json);
            if (tree == null) {
                throw denied(400, "Empty JSON document");
            }
            Set<String> fields = new HashSet<>();
            tree.fieldNames().forEachRemaining(fields::add);
            if (!tree.isObject() || !fields.equals(ENVELOPE_FIELDS) || !tree.path("operation").isTextual()) {
                throw denied(400, "Envelope fields rejected");
            }
            validateNumbers(tree);
            Operation operation = Operation.valueOf(tree.get("operation").textValue());
            if (!operation.path().equals(rawPath)) {
                throw denied(400, "Operation and path mismatch");
            }
            Request<?> envelope = mapper.readerFor(mapper.getTypeFactory()
                    .constructParametricType(Request.class, operation.payloadType())).readValue(tree);
            CapabilityKeyring.requireToken(envelope.callerAppId(), 80);
            CapabilityKeyring.requireToken(envelope.nonce(), 128);
            if (envelope.nonce().length() < 16) {
                throw denied(400, "Nonce requires at least 16 characters");
            }
            return envelope;
        } catch (IOException | IllegalArgumentException e) {
            // Parser diagnostics may contain body fragments; only the reason class is logged.
            log.warn("[capabilityAuth] JSON rejected reason={}", e.getClass().getSimpleName());
            throw denied(400, "Invalid capability JSON");
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static void validateNumbers(JsonNode node) {
        if (node.isIntegralNumber()) {
            BigInteger value = node.bigIntegerValue();
            if (value.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0 || value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                throw denied(400, "Integer outside signed 64-bit range");
            }
        } else if (node.isFloatingPointNumber()) {
            BigDecimal value = node.decimalValue();
            if (value.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0 || Math.abs((long) value.scale()) > 12) {
                throw denied(400, "Decimal outside capability precision/range");
            }
        }
        node.elements().forEachRemaining(CapabilityRequestAuthenticator::validateNumbers);
    }

    private static CapabilityAuthenticationException denied(int status, String reason) {
        return new CapabilityAuthenticationException(status, reason);
    }

    public record Verified(ServicePrincipal principal, Request<?> request, byte[] rawBody) {
        public Verified {
            rawBody = rawBody.clone();
        }

        @Override
        public byte[] rawBody() {
            return rawBody.clone();
        }
    }
}
