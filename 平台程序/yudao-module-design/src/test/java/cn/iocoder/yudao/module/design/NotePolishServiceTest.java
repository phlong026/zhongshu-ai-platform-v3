package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.design.ai.NotePolishLimiter;
import cn.iocoder.yudao.module.design.ai.NotePolishProperties;
import cn.iocoder.yudao.module.design.ai.NotePolishService;
import cn.iocoder.yudao.module.design.ai.NotePolishService.Response;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 补充需求「AI 优化」（方案 A）：后端不再持有 Provider 密钥，以内部签名调用 ai-runtime 的
 * POST /internal/note-polish；用户级限流与全局并发上限在后端。
 */
class NotePolishServiceTest {

    private static final String SECRET = "test-internal-secret-0123456789abcdef";
    private static final long USER = 1001L;

    private NotePolishProperties properties(String runtimeUrl) {
        NotePolishProperties p = new NotePolishProperties();
        p.setRuntimeUrl(runtimeUrl);
        return p;
    }

    private NotePolishService service(String runtimeUrl, String secret, NotePolishService.Transport transport) {
        return new NotePolishService(properties(runtimeUrl), secret, transport, new NotePolishLimiter());
    }

    private static String expectedSignature(String timestamp, String body) throws Exception {
        String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(
                (timestamp + "\nPOST\n/internal/note-polish\n" + bodyHash).getBytes(StandardCharsets.UTF_8)));
    }

    private static Response ok(String polished) {
        return new Response(200, "{\"polished\":\"" + polished + "\"}");
    }

    @Test
    void forwardsToRuntimeWithInternalSignatureAndReturnsPolished() throws Exception {
        AtomicReference<String> url = new AtomicReference<>(), body = new AtomicReference<>();
        AtomicReference<Map<String, String>> headers = new AtomicReference<>();
        NotePolishService service = service("http://127.0.0.1:8081/", SECRET, (u, b, h) -> {
            url.set(u); body.set(b); headers.set(h);
            return ok("老人房设于一层南向");
        });
        assertThat(service.polish(USER, "老人房在一楼", "ELEVATION")).isEqualTo("老人房设于一层南向");
        assertThat(url.get()).isEqualTo("http://127.0.0.1:8081/internal/note-polish");
        assertThat(body.get()).contains("\"note\":\"老人房在一楼\"").contains("\"line\":\"ELEVATION\"");
        String timestamp = headers.get().get("X-ZS-Timestamp");
        assertThat(Math.abs(System.currentTimeMillis() / 1000 - Long.parseLong(timestamp))).isLessThan(5);
        assertThat(headers.get().get("X-ZS-Signature")).isEqualTo(expectedSignature(timestamp, body.get()));
        assertThat(String.join("|", headers.get().values())).as("请求头里不得出现共享密钥本身").doesNotContain(SECRET);
    }

    @Test
    void goldenVectorMatchesTheNodeRuntimeVerifier() throws Exception {
        // 与 ai-runtime/test/note-polish.test.mjs 的"黄金向量"同一组固定输入；签名值由 Node 独立算出。
        // 任何一端改动签名口径（路径、换行、哈希对象）这里或那里都会变红
        assertThat(NotePolishService.signature("golden-secret-0123456789abcdef", "1760000000", "{\"note\":\"老人房在一楼\",\"line\":\"FLAT\"}"))
                .isEqualTo("363c3ff8e9d0ecf0f2626b7a4c54b5f334a3cc428094c5a26ce381a0df1dd454");
    }

    @Test
    void serviceReceiptCanonicalHashMatchesRuntimeAndTrimsUnicodeWhitespace() {
        assertThat(NotePolishService.serviceRequestHash("老人房在一楼", "FLAT"))
                .isEqualTo("27949f96baba1e796195d02dbd9181b4d31b437909b2fa29a5a4a4f483544b66");
        assertThat(NotePolishService.serviceRequestHash("白墙灰瓦", "ELEVATION"))
                .isEqualTo("c5a543d09fdf83f005d30f59787ed24b5ced80ad90e1ce0895a544f43d42fc17");
        assertThat(NotePolishService.serviceRequestHash("\uFEFF\u00A0老人房在一楼\u3000", "FLAT"))
                .isEqualTo(NotePolishService.serviceRequestHash("老人房在一楼", "FLAT"));
    }

    @Test
    void serviceHashMatchesRuntimeForControlCharactersSurrogatePairsAndLoneSurrogates() {
        assertThat(NotePolishService.serviceRequestHash("房"+(char)0x1f+"间","FLAT"))
                .isEqualTo("e96b9694fb7dbac773cb389dffd62d22a10bfa44ef073e6e988f69fcbbb944c0");
        assertThat(NotePolishService.serviceRequestHash("房"+new String(Character.toChars(0x1f3e0))+"间","FLAT"))
                .isEqualTo("c1f153ba512a0776a69d7548ebcf6fd8edfc6d964cbd4828a7bf38c58b13fb6d");
        assertThat(NotePolishService.serviceRequestHash("房"+(char)0xd800+"间","FLAT"))
                .isEqualTo("0688afeb4b29d96333f0742359d4e707cd614f81ca6bf759105c50a3cec5b4bf");
        assertThat(NotePolishService.serviceRequestHash("房"+new String(new char[]{34,92,47,8,12,10,13,9,0,31})+"间","FLAT"))
                .isEqualTo("268f83212856f463914dd0b3b36972c926cd3d667a3a8d7cefc9bb23a8861777");
    }

    @Test
    void serviceSubmitPreservesLoneSurrogateInUtf8RequestBody() {
        String note="房"+(char)0xd800+"间";
        var accepted=service("http://127.0.0.1:8081",SECRET,(url,body,headers) -> {
            var wirePayload=new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(body.getBytes(StandardCharsets.UTF_8));
            assertThat(wirePayload.path("note").asText()).isEqualTo(note);
            assertThat(body).contains("\\ud800");
            return new Response(200,"{\"status\":\"SUCCEEDED\",\"requestHash\":\"0688afeb4b29d96333f0742359d4e707cd614f81ca6bf759105c50a3cec5b4bf\",\"polished\":\"一层设老人房\"}");
        });
        assertThat(accepted.polishForService(USER,note,"FLAT","svc:surrogate-body"))
                .isEqualTo("一层设老人房");
    }

    @Test
    void serviceSubmitRequiresSuccessfulMatchingDurableReceipt() {
        String hash=NotePolishService.serviceRequestHash("老人房在一楼","FLAT");
        var accepted=service("http://127.0.0.1:8081",SECRET,(url,body,headers) -> {
            assertThat(body).contains("\"receiptId\":\"svc:receipt-1\"");
            return new Response(200,"{\"status\":\"SUCCEEDED\",\"requestHash\":\""+hash+"\",\"polished\":\"一层设老人房\"}");
        });
        assertThat(accepted.polishForService(USER,"老人房在一楼","FLAT","svc:receipt-1")).isEqualTo("一层设老人房");
        var unknown=service("http://127.0.0.1:8081",SECRET,(url,body,headers) -> new Response(200,"{\"status\":\"UNKNOWN\"}"));
        assertThatThrownBy(() -> unknown.polishForService(USER,"老人房在一楼","FLAT","svc:receipt-2")).isInstanceOf(ServiceException.class);
    }

    @Test
    void receiptQuerySignsItsOwnPathAndNeverInvokesSubmit() {
        var recovery=service("http://127.0.0.1:8081",SECRET,(url,body,headers) -> {
            assertThat(url).endsWith("/internal/note-polish/receipt");
            String bodyHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            String expected=HexFormat.of().formatHex(mac.doFinal((headers.get("X-ZS-Timestamp")+"\nPOST\n/internal/note-polish/receipt\n"+bodyHash).getBytes(StandardCharsets.UTF_8)));
            assertThat(headers.get("X-ZS-Signature")).isEqualTo(expected);
            return new Response(200,"{\"status\":\"UNKNOWN\",\"requestHash\":\"receipt-hash\"}");
        });
        assertThat(recovery.serviceReceipt(USER,"svc:receipt-1").status()).isEqualTo("UNKNOWN");
    }

    @Test
    void receiptPollingDoesNotConsumeModelQuotaNeededForRecovery() {
        String hash=NotePolishService.serviceRequestHash("老人房在一楼","FLAT");
        var recovery=new NotePolishService(properties("http://127.0.0.1:8081"),SECRET,(url,body,headers) ->
                url.endsWith("/receipt")?new Response(200,"{\"status\":\"NOT_FOUND\"}"):
                        new Response(200,"{\"status\":\"SUCCEEDED\",\"requestHash\":\""+hash+"\",\"polished\":\"一层设老人房\"}"),
                new NotePolishLimiter(1,1,1,System::currentTimeMillis));
        for(int i=0;i<12;i++) assertThat(recovery.serviceReceipt(USER,"svc:receipt-1").status()).isEqualTo("NOT_FOUND");
        assertThat(recovery.polishForService(USER,"老人房在一楼","FLAT","svc:receipt-1")).isEqualTo("一层设老人房");
    }

    @Test
    void unconfiguredRuntimeOrSecretFailsFastWithoutOutboundCall() {
        NotePolishService.Transport never = (u, b, h) -> { throw new AssertionError("不应出站调用"); };
        for (NotePolishService s : new NotePolishService[]{service("", SECRET, never), service(null, SECRET, never),
                service("http://127.0.0.1:8081", "", never), service("http://127.0.0.1:8081", null, never)}) {
            assertThatThrownBy(() -> s.polish(USER, "老人房在一楼", "FLAT"))
                    .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getMessage()).contains("暂未配置"));
        }
    }

    @Test
    void runtimeUrlMustBeAnOriginWithoutPathOrCredentials() {
        NotePolishService.Transport never = (u, b, h) -> { throw new AssertionError("不应出站调用"); };
        for (String bad : new String[]{"http://127.0.0.1:8081/prefix", "ftp://127.0.0.1:8081", "http://user:pw@127.0.0.1:8081", "not a url"}) {
            assertThatThrownBy(() -> service(bad, SECRET, never).polish(USER, "老人房", "FLAT"))
                    .as(bad).isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getMessage()).contains("暂未配置"));
        }
    }

    @Test
    void blankNoteRejectedAndOverlongNoteTruncatedBeforeSending() {
        AtomicReference<String> body = new AtomicReference<>();
        NotePolishService service = service("http://127.0.0.1:8081", SECRET, (u, b, h) -> { body.set(b); return ok("好"); });
        assertThatThrownBy(() -> service.polish(USER, "  ", "FLAT"))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getMessage()).contains("请先输入"));
        assertThat(body.get()).isNull();
        service.polish(USER, "长".repeat(300), "FLAT");
        assertThat(body.get()).contains("长".repeat(200)).doesNotContain("长".repeat(201));
    }

    @Test
    void runtimeFailuresMapToFriendlyUpstreamErrorNotClientError() {
        for (Response failure : new Response[]{new Response(502, "{\"error\":\"PROVIDER_FAILED\"}"), new Response(500, "boom"),
                new Response(401, "{}"), new Response(200, "{\"polished\":\"  \"}"), new Response(200, "not-json"), new Response(200, "{}")}) {
            NotePolishService service = service("http://127.0.0.1:8081", SECRET, (u, b, h) -> failure);
            assertThatThrownBy(() -> service.polish(USER, "老人房在一楼", "FLAT"))
                    .isInstanceOfSatisfying(ServiceException.class, e -> {
                        assertThat(e.getMessage()).isEqualTo("AI 优化失败，请稍后再试");
                        assertThat(e.getCode()).as("上游失败不应归为 400 客户端错误").isEqualTo(502);
                    });
        }
        NotePolishService timeout = service("http://127.0.0.1:8081", SECRET, (u, b, h) -> { throw new java.net.http.HttpTimeoutException("timeout"); });
        assertThatThrownBy(() -> timeout.polish(USER, "老人房在一楼", "FLAT"))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(502));
    }

    @Test
    void interruptedRequestRestoresInterruptFlag() {
        NotePolishService service = service("http://127.0.0.1:8081", SECRET, (u, b, h) -> { throw new InterruptedException("stop"); });
        try {
            assertThatThrownBy(() -> service.polish(USER, "老人房在一楼", "FLAT")).isInstanceOf(ServiceException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // 清除标志，避免影响后续用例
        }
    }

    @Test
    void runtimeBusyOrQuotaMapsToRetryLater() {
        for (String error : new String[]{"BUSY", "QUOTA"}) {
            NotePolishService service = service("http://127.0.0.1:8081", SECRET,
                    (u, b, h) -> new Response(429, "{\"error\":\"" + error + "\"}"));
            assertThatThrownBy(() -> service.polish(USER, "老人房在一楼", "FLAT"))
                    .isInstanceOfSatisfying(ServiceException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(429);
                        assertThat(e.getMessage()).contains("稍后");
                    });
        }
    }

    @Test
    void overlongRuntimeOutputIsClampedDefensively() {
        NotePolishService service = service("http://127.0.0.1:8081", SECRET, (u, b, h) -> ok("长".repeat(300)));
        assertThat(service.polish(USER, "x", "FLAT").length()).isEqualTo(200);
    }

    // ===== H7：用户级限流与全局并发上限 =====

    @Test
    void perAccountRateLimitIsEnforcedPerMinuteAndPerDay() {
        AtomicLong now = new AtomicLong(1_000_000L);
        NotePolishLimiter limiter = new NotePolishLimiter(3, 5, 8, now::get);
        NotePolishService service = new NotePolishService(properties("http://127.0.0.1:8081"), SECRET, (u, b, h) -> ok("好"), limiter);
        for (int i = 0; i < 3; i++) service.polish(USER, "a", "FLAT");
        assertThatThrownBy(() -> service.polish(USER, "a", "FLAT"))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(429));
        assertThat(service.polish(USER + 1, "a", "FLAT")).as("其他账号不受影响").isEqualTo("好");

        now.addAndGet(61_000);                       // 滑出分钟窗口
        service.polish(USER, "a", "FLAT");
        service.polish(USER, "a", "FLAT");           // 今日第 5 次
        now.addAndGet(61_000);
        assertThatThrownBy(() -> service.polish(USER, "a", "FLAT"))
                .as("当日上限 5 次").isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(429));
        now.addAndGet(24L * 3600 * 1000);            // 次日恢复
        assertThat(service.polish(USER, "a", "FLAT")).isEqualTo("好");
    }

    @Test
    void globalConcurrencyCapRejectsExtraRequestsAndReleasesPermits() throws Exception {
        NotePolishLimiter limiter = new NotePolishLimiter(100, 1000, 2, System::currentTimeMillis);
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        NotePolishService service = new NotePolishService(properties("http://127.0.0.1:8081"), SECRET, (u, b, h) -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return ok("好");
        }, limiter);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<String> first = pool.submit(() -> service.polish(1L, "a", "FLAT"));
            Future<String> second = pool.submit(() -> service.polish(2L, "a", "FLAT"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.polish(3L, "a", "FLAT"))
                    .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(429));
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("好");
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("好");
            assertThat(service.polish(3L, "a", "FLAT")).as("许可已释放").isEqualTo("好");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failedCallsStillReleaseConcurrencyPermit() {
        NotePolishLimiter limiter = new NotePolishLimiter(100, 1000, 1, System::currentTimeMillis);
        NotePolishService failing = new NotePolishService(properties("http://127.0.0.1:8081"), SECRET,
                (u, b, h) -> { throw new java.io.IOException("down"); }, limiter);
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> failing.polish(USER, "a", "FLAT"))
                    .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(502));
        }
    }
}
