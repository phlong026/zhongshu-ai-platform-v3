package cn.iocoder.yudao.designprovider;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.design.ai.NotePolishLimiter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;

/** B has no daily business quota; only burst and concurrent-call protection remain. */
final class ProviderNotePolishLimiter extends NotePolishLimiter {
    private static final long MINUTE_MS = 60_000L;
    private static final int PURGE_THRESHOLD = 10_000;

    private final int perMinute;
    private final Semaphore permits;
    private final LongSupplier clock;
    private final int purgeThreshold;
    private final ConcurrentHashMap<Long, Deque<Long>> calls = new ConcurrentHashMap<>();

    ProviderNotePolishLimiter(int perMinute, int maxConcurrent, LongSupplier clock) {
        this(perMinute, maxConcurrent, clock, PURGE_THRESHOLD);
    }

    ProviderNotePolishLimiter(int perMinute, int maxConcurrent, LongSupplier clock, int purgeThreshold) {
        super(1, 1, 1, clock); // Parent state is unused; public contract is overridden below.
        if (perMinute < 1 || maxConcurrent < 1 || purgeThreshold < 1) {
            throw new IllegalArgumentException("Provider note polish safeguards must be positive");
        }
        this.perMinute = perMinute;
        this.permits = new Semaphore(maxConcurrent);
        this.clock = clock;
        this.purgeThreshold = purgeThreshold;
    }

    @Override
    public void acquire(long accountId) {
        long now = clock.getAsLong();
        calls.compute(accountId, (ignored, existing) -> {
            Deque<Long> history = existing == null ? new ArrayDeque<>() : existing;
            while (!history.isEmpty() && now - history.peekFirst() >= MINUTE_MS) {
                history.pollFirst();
            }
            if (history.size() >= perMinute) {
                throw new ServiceException(429, "操作太频繁，请稍后再试");
            }
            if (!permits.tryAcquire()) {
                throw new ServiceException(429, "AI 优化繁忙，请稍后再试");
            }
            history.addLast(now);
            return history;
        });
        if (calls.size() > purgeThreshold) {
            calls.forEachKey(Long.MAX_VALUE, key -> calls.computeIfPresent(key, (ignored, history) -> {
                while (!history.isEmpty() && now - history.peekFirst() >= MINUTE_MS) {
                    history.pollFirst();
                }
                return history.isEmpty() ? null : history;
            }));
        }
    }

    @Override
    public void release() {
        permits.release();
    }
}
