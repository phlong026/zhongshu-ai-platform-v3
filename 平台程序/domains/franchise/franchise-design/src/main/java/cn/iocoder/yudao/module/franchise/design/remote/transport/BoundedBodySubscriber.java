package cn.iocoder.yudao.module.franchise.design.remote.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Applies the same streaming byte limit to successful and error responses. */
final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int maximum;
    private final ByteArrayOutputStream bytes;
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private Flow.Subscription subscription;

    BoundedBodySubscriber(int maximum) {
        this.maximum = maximum;
        bytes = new ByteArrayOutputStream(Math.min(maximum, 8192));
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return body;
    }

    @Override
    public synchronized void onSubscribe(Flow.Subscription next) {
        if (subscription != null || body.isDone()) {
            next.cancel();
            return;
        }
        subscription = next;
        subscription.request(1);
    }

    @Override
    public synchronized void onNext(List<ByteBuffer> buffers) {
        if (body.isDone()) {
            return;
        }
        for (ByteBuffer buffer : buffers) {
            if (buffer.remaining() > maximum - bytes.size()) {
                cancel(new IOException("Capability response exceeds byte limit"));
                return;
            }
            byte[] chunk = new byte[Math.min(buffer.remaining(), 8192)];
            while (buffer.hasRemaining()) {
                int length = Math.min(buffer.remaining(), chunk.length);
                buffer.get(chunk, 0, length);
                bytes.write(chunk, 0, length);
            }
        }
        subscription.request(1);
    }

    @Override
    public synchronized void onError(Throwable failure) {
        cancel(failure);
    }

    @Override
    public synchronized void onComplete() {
        if (!body.isDone()) {
            body.complete(bytes.toByteArray());
        }
    }

    synchronized void cancel(Throwable failure) {
        if (subscription != null) {
            subscription.cancel();
        }
        body.completeExceptionally(failure);
    }
}
