package cn.iocoder.yudao.module.franchise.design.remote.transport;

import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Operation;
import cn.iocoder.yudao.module.franchise.api.capability.security.CapabilitySignature;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DesignCapabilityTransportTest {
    private static final byte[] KEY = "test-capability-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void close() {
        release.countDown();
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private DesignCapabilityTransport transport(com.sun.net.httpserver.HttpHandler handler, int maxResponse) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", handler);
        server.start();
        return new DesignCapabilityTransport(CapabilityTransportSettings.forLoopbackTests(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Duration.ofMillis(200),
                Duration.ofMillis(500), 4096, maxResponse, "key-1", KEY));
    }

    @Test
    void sendsIdenticalSignedBytesToFixedOperationPathExactlyOnce() throws Exception {
        byte[] body = "{\"text\":\"老人房\\n二层\"} ".getBytes(StandardCharsets.UTF_8);
        AtomicReference<byte[]> received = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> actualSignature = new AtomicReference<>();
        AtomicReference<String> timestamp = new AtomicReference<>();
        AtomicInteger count = new AtomicInteger();
        var transport = transport(exchange -> {
            count.incrementAndGet();
            received.set(exchange.getRequestBody().readAllBytes());
            path.set(exchange.getRequestURI().toString());
            actualSignature.set(exchange.getRequestHeaders().getFirst(CapabilitySignature.SIGNATURE_HEADER));
            timestamp.set(exchange.getRequestHeaders().getFirst(CapabilitySignature.TIMESTAMP_HEADER));
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        }, 128);
        var response = transport.exchange(Operation.CREATE_PROJECT, body);
        assertEquals(200, response.statusCode());
        assertArrayEquals(body, received.get());
        assertEquals(Operation.CREATE_PROJECT.path(), path.get());
        assertEquals(CapabilitySignature.sign(KEY, timestamp.get(), "POST", path.get(), body), actualSignature.get());
        assertEquals(1, count.get());
    }

    @Test
    void productionOriginRejectsHttpUserInfoPathQueryFragmentAndMissingHost() {
        for (String origin : new String[]{"http://127.0.0.1:9000", "http://example.com", "https://user:pass@example.com",
                "https://example.com/path", "https://example.com?x=1", "https://example.com#fragment", "file:///tmp/x"}) {
            assertThrows(IllegalArgumentException.class, () -> new CapabilityTransportSettings(URI.create(origin),
                    Duration.ofSeconds(1), Duration.ofSeconds(1), 100, 100, "key-1", KEY), origin);
        }
        assertThrows(IllegalArgumentException.class, () -> CapabilityTransportSettings.forLoopbackTests(
                URI.create("http://example.com"), Duration.ofSeconds(1), Duration.ofSeconds(1), 100, 100, "key-1", KEY));
        assertDoesNotThrow(() -> new CapabilityTransportSettings(URI.create("https://design.example.com"),
                Duration.ofSeconds(1), Duration.ofSeconds(2), 100, 100, "key-1", KEY));
    }

    @Test
    void rejectsRedirectWithoutFollowingItOrRetryingCreate() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var transport = transport(exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", "/redirect-target");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        }, 32);
        CapabilityTransportException failure = assertThrows(CapabilityTransportException.class,
                () -> transport.exchange(Operation.CREATE_PROJECT, new byte[]{1}));
        assertTrue(failure.outcomeUnknown());
        assertEquals(1, calls.get());
    }

    @Test
    void boundsActualChunkedSuccessAndErrorBodies() throws Exception {
        AtomicInteger status = new AtomicInteger(200);
        AtomicInteger calls = new AtomicInteger();
        var transport = transport(exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(status.get(), 0);
            try (var output = exchange.getResponseBody()) {
                output.write(new byte[1024]);
            }
        }, 128);
        assertTrue(assertThrows(CapabilityTransportException.class,
                () -> transport.exchange(Operation.CREATE_PROJECT, new byte[]{1})).outcomeUnknown());
        status.set(500);
        assertTrue(assertThrows(CapabilityTransportException.class,
                () -> transport.exchange(Operation.CREATE_PROJECT, new byte[]{1})).outcomeUnknown());
        assertEquals(2, calls.get());
    }

    @Test
    void completeDeadlineCancelsStallAfterResponseHeaders() throws Exception {
        var transport = transport(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(1);
            exchange.getResponseBody().flush();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        }, 128);
        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertTrue(assertThrows(CapabilityTransportException.class,
                        () -> transport.exchange(Operation.CREATE_PROJECT, new byte[]{1})).outcomeUnknown()));
    }

    @Test
    void slowProgressDoesNotExtendTheCompleteBodyDeadline() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var transport = transport(exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                for (int i = 0; i < 40; i++) {
                    output.write(' ');
                    output.flush();
                    if (release.await(100, TimeUnit.MILLISECONDS)) {
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 128);
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertTrue(assertThrows(CapabilityTransportException.class,
                () -> transport.exchange(Operation.CREATE_PROJECT, new byte[]{1})).outcomeUnknown()));
        assertEquals(1, calls.get());
    }

    @Test
    void responseLimitIsInclusiveAndReadFailuresAreNotWriteUncertainty() throws Exception {
        var transport = transport(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(500, 128);
            exchange.getResponseBody().write(new byte[128]);
            exchange.close();
        }, 128);
        var response = transport.exchange(Operation.LIST_PROJECTS, new byte[]{1});
        assertEquals(500, response.statusCode());
        assertEquals(128, response.responseBody().length);
        assertFalse(assertThrows(CapabilityTransportException.class,
                () -> transport.exchange(Operation.LIST_PROJECTS, new byte[4097])).outcomeUnknown());
    }

    @Test
    void subscriberCancelsBeforeCopyingAnOversizedChunkAndOnEarlyDeadline() {
        var subscriber = new BoundedBodySubscriber(8);
        var cancelled = new AtomicInteger();
        java.util.concurrent.Flow.Subscription subscription = new java.util.concurrent.Flow.Subscription() {
            @Override
            public void request(long count) { assertEquals(1, count); }
            @Override
            public void cancel() { cancelled.incrementAndGet(); }
        };
        subscriber.onSubscribe(subscription);
        subscriber.onNext(java.util.List.of(java.nio.ByteBuffer.wrap(new byte[9])));
        assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
        assertEquals(1, cancelled.get());
        var pendingHeaders = new BoundedBodySubscriber(8);
        pendingHeaders.cancel(new java.io.IOException("deadline before headers"));
        pendingHeaders.onSubscribe(subscription);
        assertEquals(2, cancelled.get());
    }

    @Test
    void connectionLossAfterRequestBodyDoesNotRetryACreate() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var transport = transport(exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.close();
        }, 128);
        assertTrue(assertThrows(CapabilityTransportException.class,
                () -> transport.exchange(Operation.CREATE_PROJECT, new byte[]{1})).outcomeUnknown());
        assertEquals(1, calls.get());
    }

    @Test
    void truncatedResponseNeverLooksLikeSuccess() throws Exception {
        var transport = transport(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 20);
            exchange.getResponseBody().write(1);
            exchange.close();
        }, 128);
        assertTrue(assertThrows(CapabilityTransportException.class,
                () -> transport.exchange(Operation.CREATE_PROJECT, new byte[]{1})).outcomeUnknown());
    }

    @Test
    void oversizedRequestIsRejectedBeforeSendingAndIsNotUnknown() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var transport = transport(exchange -> {
            calls.incrementAndGet();
            exchange.close();
        }, 128);
        assertFalse(assertThrows(CapabilityTransportException.class,
                () -> transport.exchange(Operation.CREATE_PROJECT, new byte[4097])).outcomeUnknown());
        assertEquals(0, calls.get());
    }
}
