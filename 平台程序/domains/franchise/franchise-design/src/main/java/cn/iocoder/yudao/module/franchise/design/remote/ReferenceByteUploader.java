package cn.iocoder.yudao.module.franchise.design.remote;

import cn.iocoder.yudao.framework.common.exception.ServiceException;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;

/** 只向部署配置的精确HTTPS对象存储主机PUT；不带平台令牌、不跟随跳转。 */
@Slf4j
public class ReferenceByteUploader {
    private final Set<String> hosts;
    private final boolean loopbackTest;
    private final HttpClient client =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    public ReferenceByteUploader(Set<String> hosts) {
        this(hosts, false);
    }

    ReferenceByteUploader(Set<String> hosts, boolean loopbackTest) {
        this.hosts = Set.copyOf(hosts);
        this.loopbackTest = loopbackTest;
        for (String host : hosts)
            if (host.isBlank()
                    || !host.equals(host.toLowerCase(java.util.Locale.ROOT))
                    || host.contains(":")
                    || host.contains("/")
                    || host.contains("*")
                    || host.endsWith("."))
                throw new IllegalArgumentException(
                        "Reference upload hosts must be exact lowercase DNS names");
    }

    public void requireReady() {
        if (hosts.isEmpty()) throw failure("未配置设计参考图对象存储主机");
    }

    public void put(String url, byte[] bytes, String mime) {
        requireReady();
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException e) {
            throw failure("设计服务上传地址无效");
        }
        boolean test =
                loopbackTest && "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost());
        if ((!test && !"https".equals(uri.getScheme()))
                || !hosts.contains(uri.getHost())
                || uri.getRawUserInfo() != null
                || uri.getFragment() != null
                || (!test && uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getPath() == null
                || uri.getPath().isEmpty()
                || bytes.length == 0
                || bytes.length > (20 << 20)
                || !Set.of("image/png", "image/jpeg").contains(mime))
            throw failure("设计服务上传地址或素材不受支持");
        var request =
                HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", mime)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .build();
        CompletableFuture<HttpResponse<Void>> pending =
                client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        try {
            int status = pending.get(30, TimeUnit.SECONDS).statusCode();
            if (status < 200 || status >= 300) throw failure("参考图传输失败，请使用原业务键重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.cancel(true);
            throw failure("参考图传输被中断，请重试");
        } catch (ExecutionException | TimeoutException e) {
            pending.cancel(true);
            log.warn(
                    "Reference byte upload failed host={} error={}",
                    uri.getHost(),
                    e.getClass().getSimpleName());
            throw failure("参考图传输失败，请使用原业务键重试");
        }
    }

    private static ServiceException failure(String message) {
        return new ServiceException(1_112_100_003, message);
    }
}
