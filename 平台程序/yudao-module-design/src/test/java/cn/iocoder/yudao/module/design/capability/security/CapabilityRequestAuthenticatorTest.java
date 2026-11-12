package cn.iocoder.yudao.module.design.capability.security;

import cn.iocoder.yudao.framework.signature.core.redis.ApiSignatureRedisDAO;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Operation;
import cn.iocoder.yudao.module.franchise.api.capability.security.CapabilityKeyring;
import cn.iocoder.yudao.module.franchise.api.capability.security.CapabilitySignature;
import cn.iocoder.yudao.module.franchise.api.capability.security.ReplayWindow;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CapabilityRequestAuthenticatorTest {
    static final byte[] KEY = "test-capability-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    static final byte[] KEY2 = "test-rotated-key-0123456789abcdef012".getBytes(StandardCharsets.UTF_8);
    static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1760000000), ZoneOffset.UTC);

    static byte[] envelope(String nonce) {
        return ("{\"callerAppId\":\"platform-b\",\"orgId\":42,\"actor\":{\"actorUserId\":7,\"actorPostId\":9,\"actorRoleCode\":\"OWNER\"},"
                + "\"operation\":\"LIST_PROJECTS\",\"requestKey\":\"request-1\",\"nonce\":\"" + nonce
                + "\",\"payload\":{\"cursor\":null,\"limit\":10}}").getBytes(StandardCharsets.UTF_8);
    }

    static MockHttpServletRequest request(byte[] body) {
        var request = new MockHttpServletRequest("POST", Operation.LIST_PROJECTS.path());
        request.setContentType("application/json; charset=utf-8");
        request.setContent(body);
        request.addHeader(CapabilitySignature.KEY_ID_HEADER, "key-1");
        request.addHeader(CapabilitySignature.TIMESTAMP_HEADER, "1760000000");
        request.addHeader(CapabilitySignature.SIGNATURE_HEADER,
                CapabilitySignature.sign(KEY, "1760000000", "POST", request.getRequestURI(), body));
        return request;
    }

    private CapabilityRequestAuthenticator authenticator(ApiSignatureRedisDAO dao) {
        return new CapabilityRequestAuthenticator(new CapabilityKeyring(Map.of(
                "key-1", new CapabilityKeyring.Key("platform-b", KEY, Set.of("LIST_PROJECTS")),
                "key-2", new CapabilityKeyring.Key("platform-b", KEY2, Set.of("LIST_PROJECTS")))),
                new ReplayWindow(300, 5), CLOCK, dao,
                (caller, org) -> new ServicePrincipal(caller, org, 888, 999), 4096);
    }

    @Test
    void validSignatureUsesCallerScopedNonceAndServerSideMapping() throws Exception {
        var dao = mock(ApiSignatureRedisDAO.class);
        when(dao.setNonce(anyString(), anyString(), anyInt(), any())).thenReturn(true);
        var verified = authenticator(dao).authenticate(request(envelope("nonce-000000000001")));
        assertEquals(new ServicePrincipal("platform-b", 42, 888, 999), verified.principal());
        assertEquals(Operation.LIST_PROJECTS, verified.request().operation());
        assertInstanceOf(cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Page.class,
                verified.request().payload());
        verify(dao).setNonce("design-capability:platform-b", "nonce-000000000001", 621, TimeUnit.SECONDS);
    }

    @Test
    void requestLevelResolverDefaultsToLegacyCallerOrganizationMapping() {
        CallerOrganizationResolver legacy = (caller, org) -> new ServicePrincipal(caller, org, 888, 999);
        var request = new cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Request<>(
                "platform-b", 42, new cn.iocoder.yudao.module.franchise.api.DesignServicePort.Actor(7, 9, "OWNER"),
                Operation.GET_SETTLEMENT, "request-1", "nonce-000000000001",
                new cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Job(17));
        assertEquals(legacy.resolve("platform-b", 42), legacy.resolveRequest(request));
    }

    @Test
    void invalidSignatureNeverInvokesRequestLevelResolverForHistoricalQueries() {
        var dao = mock(ApiSignatureRedisDAO.class);
        var resolver = mock(CallerOrganizationResolver.class);
        for (Operation operation : new Operation[]{Operation.QUERY_RECEIPT, Operation.GET_SETTLEMENT}) {
            var request = historicalQuery(operation);
            request.removeHeader(CapabilitySignature.SIGNATURE_HEADER);
            request.addHeader(CapabilitySignature.SIGNATURE_HEADER, "0".repeat(64));
            assertEquals(401, assertThrows(CapabilityAuthenticationException.class,
                    () -> requestLevelAuthenticator(dao, resolver).authenticate(request)).status());
        }
        verifyNoInteractions(resolver, dao);
    }

    @Test
    void requestLevelResolverReceivesStrictlyValidatedTypedRequestAfterNonceClaim() {
        for (Operation operation : new Operation[]{Operation.QUERY_RECEIPT, Operation.GET_SETTLEMENT}) {
            var dao = mock(ApiSignatureRedisDAO.class);
            when(dao.setNonce(anyString(), anyString(), anyInt(), any())).thenReturn(true);
            var resolver = mock(CallerOrganizationResolver.class);
            when(resolver.resolveRequest(any())).thenReturn(new ServicePrincipal("platform-b", 42, 888, 999));
            var authenticator = requestLevelAuthenticator(dao, resolver);
            var malformed = historicalQuery(operation);
            malformed.setContent(new String(malformed.getContentAsByteArray(), StandardCharsets.UTF_8)
                    .replace("\"orgId\":42", "\"orgId\":42,\"orgId\":43").getBytes(StandardCharsets.UTF_8));
            malformed.removeHeader(CapabilitySignature.SIGNATURE_HEADER);
            malformed.addHeader(CapabilitySignature.SIGNATURE_HEADER, CapabilitySignature.sign(KEY, "1760000000",
                    "POST", operation.path(), malformed.getContentAsByteArray()));
            assertEquals(400, assertThrows(CapabilityAuthenticationException.class,
                    () -> authenticator.authenticate(malformed)).status());
            verifyNoInteractions(resolver, dao);

            var verified = authenticator.authenticate(historicalQuery(operation));
            assertEquals(operation, verified.request().operation());
            assertInstanceOf(operation.payloadType(), verified.request().payload());
            assertEquals("platform-b", verified.request().callerAppId());
            assertEquals(42, verified.request().orgId());
            assertEquals("request-1", verified.request().requestKey());
            var order = inOrder(dao, resolver);
            order.verify(dao).setNonce("design-capability:platform-b", "nonce-000000000001", 621, TimeUnit.SECONDS);
            order.verify(resolver).resolveRequest(same(verified.request()));
            verify(resolver, never()).resolve(anyString(), anyLong());
        }
    }

    private CapabilityRequestAuthenticator requestLevelAuthenticator(ApiSignatureRedisDAO dao, CallerOrganizationResolver resolver) {
        return new CapabilityRequestAuthenticator(new CapabilityKeyring(Map.of("key-1",
                new CapabilityKeyring.Key("platform-b", KEY, Set.of("QUERY_RECEIPT", "GET_SETTLEMENT")))),
                new ReplayWindow(300, 5), CLOCK, dao, resolver, 4096);
    }

    private MockHttpServletRequest historicalQuery(Operation operation) {
        String payload = operation == Operation.QUERY_RECEIPT
                ? "{\"operation\":\"CREATE_PROJECT\",\"requestKey\":\"original-command\"}" : "{\"jobId\":17}";
        byte[] body = new String(envelope("nonce-000000000001"), StandardCharsets.UTF_8)
                .replace("\"LIST_PROJECTS\"", "\"" + operation.name() + "\"")
                .replace("{\"cursor\":null,\"limit\":10}", payload).getBytes(StandardCharsets.UTF_8);
        var request = request(body);
        request.setRequestURI(operation.path());
        request.removeHeader(CapabilitySignature.SIGNATURE_HEADER);
        request.addHeader(CapabilitySignature.SIGNATURE_HEADER,
                CapabilitySignature.sign(KEY, "1760000000", "POST", operation.path(), body));
        return request;
    }

    @Test
    void redisFalseNullAndExceptionAllFailClosed() {
        for (Boolean outcome : new Boolean[]{false, null}) {
            var dao = mock(ApiSignatureRedisDAO.class);
            when(dao.setNonce(anyString(), anyString(), anyInt(), any())).thenReturn(outcome);
            assertThrows(CapabilityAuthenticationException.class,
                    () -> authenticator(dao).authenticate(request(envelope("nonce-000000000001"))));
        }
        var dao = mock(ApiSignatureRedisDAO.class);
        when(dao.setNonce(anyString(), anyString(), anyInt(), any())).thenThrow(new IllegalStateException("Redis unavailable"));
        assertEquals(503, assertThrows(CapabilityAuthenticationException.class,
                () -> authenticator(dao).authenticate(request(envelope("nonce-000000000001")))).status());
    }

    @Test
    void concurrentReplayAndKeyRotationAllowExactlyOneAcceptance() throws Exception {
        var redisServer = com.github.fppt.jedismock.RedisServer.newRedisServer(0).start();
        var client = org.springframework.data.redis.connection.jedis.JedisClientConfiguration.builder()
                .connectTimeout(java.time.Duration.ofSeconds(3)).readTimeout(java.time.Duration.ofSeconds(3)).build();
        var connection = new org.springframework.data.redis.connection.jedis.JedisConnectionFactory(
                new org.springframework.data.redis.connection.RedisStandaloneConfiguration(redisServer.getHost(), redisServer.getBindPort()), client);
        connection.afterPropertiesSet();
        var redis = new org.springframework.data.redis.core.StringRedisTemplate(connection);
        var authenticator = authenticator(new ApiSignatureRedisDAO(redis));
        var executor = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        var accepted = new AtomicInteger();
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 16; i++) {
                boolean rotated = i % 2 == 0;
                futures.add(executor.submit(() -> {
                    start.await();
                    byte[] body = envelope("nonce-000000000001");
                    var request = request(body);
                    if (rotated) {
                        request.removeHeader(CapabilitySignature.KEY_ID_HEADER);
                        request.addHeader(CapabilitySignature.KEY_ID_HEADER, "key-2");
                        request.removeHeader(CapabilitySignature.SIGNATURE_HEADER);
                        request.addHeader(CapabilitySignature.SIGNATURE_HEADER,
                                CapabilitySignature.sign(KEY2, "1760000000", "POST", request.getRequestURI(), body));
                    }
                    try {
                        authenticator.authenticate(request);
                        accepted.incrementAndGet();
                    } catch (CapabilityAuthenticationException expectedReplay) {
                        assertEquals(409, expectedReplay.status());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
            assertEquals(1, accepted.get());
            Long ttl = redis.getExpire("api_signature_nonce:design-capability:platform-b:nonce-000000000001", TimeUnit.SECONDS);
            assertNotNull(ttl);
            assertTrue(ttl >= 620 && ttl <= 621);
        } finally {
            executor.shutdownNow();
            connection.destroy();
            redisServer.stop();
        }
    }

    @Test
    void nonceSurvivesTheEntireTwoVerifierClockSkewWindowAcrossRotation() throws Exception {
        var elapsed = new java.util.concurrent.atomic.AtomicLong();
        Clock redisClock = new Clock() {
            @Override
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override
            public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override
            public Instant instant() { return CLOCK.instant().plusSeconds(elapsed.get()); }
        };
        var redisServer = com.github.fppt.jedismock.RedisServer.newRedisServer(0).setClock(redisClock).start();
        var client = org.springframework.data.redis.connection.jedis.JedisClientConfiguration.builder()
                .connectTimeout(java.time.Duration.ofSeconds(3)).readTimeout(java.time.Duration.ofSeconds(3)).build();
        var connection = new org.springframework.data.redis.connection.jedis.JedisConnectionFactory(
                new org.springframework.data.redis.connection.RedisStandaloneConfiguration(redisServer.getHost(), redisServer.getBindPort()), client);
        connection.afterPropertiesSet();
        var redis = new org.springframework.data.redis.core.StringRedisTemplate(connection);
        try {
            var keys = new CapabilityKeyring(Map.of(
                    "key-1", new CapabilityKeyring.Key("platform-b", KEY, Set.of("LIST_PROJECTS")),
                    "key-2", new CapabilityKeyring.Key("platform-b", KEY2, Set.of("LIST_PROJECTS"))));
            var fast = new CapabilityRequestAuthenticator(keys, new ReplayWindow(300, 5),
                    Clock.offset(redisClock, java.time.Duration.ofSeconds(5)), new ApiSignatureRedisDAO(redis),
                    (caller, org) -> new ServicePrincipal(caller, org, 888, 999), 4096);
            var slow = new CapabilityRequestAuthenticator(keys, new ReplayWindow(300, 5),
                    Clock.offset(redisClock, java.time.Duration.ofSeconds(-5)), new ApiSignatureRedisDAO(redis),
                    (caller, org) -> new ServicePrincipal(caller, org, 888, 999), 4096);
            String timestamp = Long.toString(CLOCK.instant().getEpochSecond() + 310);
            java.util.function.Function<Boolean, MockHttpServletRequest> signedRequest = rotated -> {
                byte[] body = envelope("nonce-000000000001");
                var request = request(body);
                request.removeHeader(CapabilitySignature.KEY_ID_HEADER);
                request.addHeader(CapabilitySignature.KEY_ID_HEADER, rotated ? "key-2" : "key-1");
                request.removeHeader(CapabilitySignature.TIMESTAMP_HEADER);
                request.addHeader(CapabilitySignature.TIMESTAMP_HEADER, timestamp);
                request.removeHeader(CapabilitySignature.SIGNATURE_HEADER);
                request.addHeader(CapabilitySignature.SIGNATURE_HEADER, CapabilitySignature.sign(
                        rotated ? KEY2 : KEY, timestamp, "POST", request.getRequestURI(), body));
                return request;
            };
            assertNotNull(fast.authenticate(signedRequest.apply(false)));
            elapsed.set(611); // The old TTL expired here while the slow node still accepted the signature.
            assertEquals(409, assertThrows(CapabilityAuthenticationException.class,
                    () -> slow.authenticate(signedRequest.apply(true))).status());
            elapsed.set(620);
            assertEquals(409, assertThrows(CapabilityAuthenticationException.class,
                    () -> slow.authenticate(signedRequest.apply(false))).status());
            elapsed.set(621);
            assertNull(redis.opsForValue().get("api_signature_nonce:design-capability:platform-b:nonce-000000000001"));
            assertEquals(401, assertThrows(CapabilityAuthenticationException.class,
                    () -> slow.authenticate(signedRequest.apply(true))).status());
        } finally {
            connection.destroy();
            redisServer.stop();
        }
    }

    @Test
    void tamperingUnknownRuntimeKeyOrMismatchedCallerNeverConsumesNonce() {
        var dao = mock(ApiSignatureRedisDAO.class);
        var authenticator = authenticator(dao);
        var tampered = request(envelope("nonce-000000000001"));
        tampered.setContent(envelope("nonce-000000000002"));
        assertThrows(CapabilityAuthenticationException.class, () -> authenticator.authenticate(tampered));
        var unknown = request(envelope("nonce-000000000001"));
        unknown.removeHeader(CapabilitySignature.KEY_ID_HEADER);
        unknown.addHeader(CapabilitySignature.KEY_ID_HEADER, "runtime");
        assertThrows(CapabilityAuthenticationException.class, () -> authenticator.authenticate(unknown));
        byte[] changedCaller = new String(envelope("nonce-000000000001"), StandardCharsets.UTF_8)
                .replace("platform-b", "platform-c").getBytes(StandardCharsets.UTF_8);
        assertThrows(CapabilityAuthenticationException.class, () -> authenticator.authenticate(request(changedCaller)));
        verifyNoInteractions(dao);
    }

    @Test
    void rejectsQueryNonPostPathMismatchPayloadMismatchAndUntrustedIdentityHeaders() {
        var dao = mock(ApiSignatureRedisDAO.class);
        var authenticator = authenticator(dao);
        var query = request(envelope("nonce-000000000001"));
        query.setQueryString("");
        assertThrows(CapabilityAuthenticationException.class, () -> authenticator.authenticate(query));
        var get = request(envelope("nonce-000000000001"));
        get.setMethod("GET");
        assertThrows(CapabilityAuthenticationException.class, () -> authenticator.authenticate(get));
        var path = request(envelope("nonce-000000000001"));
        path.setRequestURI(Operation.GET_AI_JOB.path());
        assertThrows(CapabilityAuthenticationException.class, () -> authenticator.authenticate(path));
        String json = new String(envelope("nonce-000000000001"), StandardCharsets.UTF_8);
        var wrongType = request(json.replace("\"limit\":10", "\"jobId\":10").getBytes(StandardCharsets.UTF_8));
        assertThrows(CapabilityAuthenticationException.class, () -> authenticator.authenticate(wrongType));
        for (String header : new String[]{"Authorization", "tenant-id", "visit-tenant-id", "X-Post-Id", "X-User-Id", "Cookie"}) {
            var request = request(envelope("nonce-000000000001"));
            request.addHeader(header, "1");
            assertThrows(CapabilityAuthenticationException.class, () -> authenticator.authenticate(request), header);
        }
        verifyNoInteractions(dao);
    }

    @Test
    void timestampMustStillBeFreshWhenBodyReadingCompletes() {
        var seconds = new java.util.concurrent.atomic.AtomicLong(CLOCK.instant().getEpochSecond());
        Clock advancing = new Clock() {
            @Override
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override
            public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override
            public Instant instant() { return Instant.ofEpochSecond(seconds.get()); }
        };
        var dao = mock(ApiSignatureRedisDAO.class);
        when(dao.setNonce(anyString(), anyString(), anyInt(), any())).thenReturn(true);
        var authenticator = new CapabilityRequestAuthenticator(new CapabilityKeyring(Map.of("key-1",
                new CapabilityKeyring.Key("platform-b", KEY, Set.of("LIST_PROJECTS")))), new ReplayWindow(300, 5),
                advancing, dao, (caller, org) -> new ServicePrincipal(caller, org, 888, 999), 4096);
        var request = new jakarta.servlet.http.HttpServletRequestWrapper(request(envelope("nonce-000000000001"))) {
            @Override
            public jakarta.servlet.ServletInputStream getInputStream() throws java.io.IOException {
                seconds.addAndGet(306);
                return super.getInputStream();
            }
        };
        assertEquals(401, assertThrows(CapabilityAuthenticationException.class,
                () -> authenticator.authenticate(request)).status());
        verifyNoInteractions(dao);
    }

    @Test
    void strictJsonRejectsDuplicateFieldsDeepNestingOverflowCoercionAndTrailingTokens() {
        var dao = mock(ApiSignatureRedisDAO.class);
        var authenticator = authenticator(dao);
        String json = new String(envelope("nonce-000000000001"), StandardCharsets.UTF_8);
        for (String invalid : new String[]{json.replace("\"orgId\":42", "\"orgId\":42,\"orgId\":43"),
                json.replace("\"orgId\":42", "\"orgId\":9223372036854775808"),
                json.replace("\"orgId\":42", "\"orgId\":42.5"),
                json.replace("\"orgId\":42", "\"orgId\":\"42\""),
                json.replace("\"limit\":10", "\"limit\":2147483648"),
                json.replace("\"limit\":10", "\"limit\":1e999999"),
                json.replace("\"limit\":10", "\"limit\":10,\"limit\":11"),
                json.replace("\"limit\":10", "\"limit\":" + "[".repeat(40) + "1" + "]".repeat(40)),
                json + " {}", json.substring(0, json.length() - 1), json.replace("\"orgId\":42,", "")}) {
            assertThrows(CapabilityAuthenticationException.class,
                    () -> authenticator.authenticate(request(invalid.getBytes(StandardCharsets.UTF_8))));
        }
        assertEquals(413, assertThrows(CapabilityAuthenticationException.class,
                () -> authenticator.authenticate(request(new byte[4097]))).status());
        verifyNoInteractions(dao);
    }
}
