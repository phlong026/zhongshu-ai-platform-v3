package cn.iocoder.yudao.designprovider;

import cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyFilter;
import cn.iocoder.yudao.module.aiorchestration.controller.internal.InternalSignatureVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class ProviderRuntimeAuthSmokeFilterConcurrencyTest {
    private static final String PATH = ProviderRuntimeSecurityConfiguration.AUTH_SMOKE_PATH;
    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);
    private static final String SECRET = "test-only-runtime-smoke-secret-32b";

    @RepeatedTest(10)
    void concurrentInvalidAndValidRequestsLeaveValidRequestAdmitted() throws Exception {
        var harness = harness();
        String now = Long.toString(Instant.now().getEpochSecond());
        String validSignature = harness.sign(now);

        assertThat(concurrent(harness,
                new SignedRequest(now, "0".repeat(64)), new SignedRequest(now, validSignature)))
                .containsExactlyInAnyOrder(401, 204);
    }

    @Test
    void unsafeLongTimestampsFailClosedEvenIfSharedVerifierAcceptsThem() throws Exception {
        var unsafeVerifier = mock(InternalSignatureVerifier.class);
        when(unsafeVerifier.verify(anyString(), anyString(), anyString(), anyString(), any(byte[].class)))
                .thenReturn(true);
        var overflowHarness = harness(unsafeVerifier);
        assertThat(overflowHarness.invoke(Long.toString(Long.MIN_VALUE), "accepted-by-shared-verifier"))
                .isEqualTo(401);
        assertThat(overflowHarness.invoke(Long.toString(Long.MAX_VALUE), "accepted-by-shared-verifier"))
                .isEqualTo(401);
    }

    @RepeatedTest(10)
    void concurrentDifferentValidSignaturesAdmitExactlyOneIdentity() throws Exception {
        var harness = harness();
        String firstTimestamp = Long.toString(Instant.now().getEpochSecond());
        String secondTimestamp = Long.toString(Instant.now().plusSeconds(1).getEpochSecond());

        var statuses = concurrent(harness,
                new SignedRequest(firstTimestamp, harness.sign(firstTimestamp)),
                new SignedRequest(secondTimestamp, harness.sign(secondTimestamp)));

        assertThat(statuses).containsExactlyInAnyOrder(204, 404);
    }

    @RepeatedTest(10)
    void concurrentRetryOfSameSignedRequestIsIdempotent() throws Exception {
        var harness = harness();
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        var request = new SignedRequest(timestamp, harness.sign(timestamp));

        assertThat(concurrent(harness, request, request, request, request))
                .containsOnly(204);
    }

    private static Harness harness() {
        var verifier = new InternalSignatureVerifier();
        ReflectionTestUtils.setField(verifier, "sharedSecret", SECRET);
        ReflectionTestUtils.setField(verifier, "replayWindowSeconds", 300L);
        return harness(verifier);
    }

    private static Harness harness(InternalSignatureVerifier verifier) {
        var authentication = new ProviderRuntimeSecurityConfiguration.RuntimeAuthenticationFilter(
                verifier, mock(StringRedisTemplate.class), 300, () -> Instant.now().getEpochSecond());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProviderRuntimeAuthSmokeController())
                .addFilters(new CacheRequestBodyFilter(), authentication)
                .build();
        return new Harness(mvc, verifier);
    }

    private static List<Integer> concurrent(Harness harness, SignedRequest... requests) throws Exception {
        var ready = new CountDownLatch(requests.length);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(requests.length);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (var request : requests) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return harness.invoke(request.timestamp(), request.signature());
                }));
            }
            ready.await();
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var future : futures) {
                statuses.add(future.get());
            }
            return statuses;
        } finally {
            executor.shutdownNow();
        }
    }

    private record SignedRequest(String timestamp, String signature) { }

    private record Harness(MockMvc mvc, InternalSignatureVerifier verifier) {
        String sign(String timestamp) {
            return verifier.sign(timestamp, "POST", PATH, BODY);
        }

        int invoke(String timestamp, String signature) throws Exception {
            MockHttpServletRequestBuilder request = post(PATH).content(BODY)
                    .contentType("application/json")
                    .header("X-ZS-Timestamp", timestamp)
                    .header("X-ZS-Signature", signature);
            return mvc.perform(request).andReturn().getResponse().getStatus();
        }
    }
}
