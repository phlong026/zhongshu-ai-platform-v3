package cn.iocoder.yudao.module.design.ai;

import cn.iocoder.yudao.framework.common.exception.ServiceException;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;

/**
 * 补充需求润色的滥用防护：每账号滑动窗口（每分钟/每日）+ 全局并发上限。
 * 润色是同步占用请求线程的付费 LLM 调用，计费口径未定前必须先限住次数与并发。
 * 进程内计数：多实例部署时每实例各算各的，总量上限由 ai-runtime 的独立日额度兜底。
 */
public class NotePolishLimiter {

    private static final long MINUTE_MS = 60_000L;
    private static final long DAY_MS = 24L * 3600 * 1000;
    private static final int PURGE_THRESHOLD = 10_000;

    private final int perMinute;
    private final int perDay;
    private final Semaphore permits;
    private final LongSupplier clock;
    private final ConcurrentHashMap<Long, Deque<Long>> calls = new ConcurrentHashMap<>();

    public NotePolishLimiter() {
        this(10, 100, 8, System::currentTimeMillis);
    }

    public NotePolishLimiter(int perMinute, int perDay, int maxConcurrent, LongSupplier clock) {
        this.perMinute = perMinute;
        this.perDay = perDay;
        this.permits = new Semaphore(maxConcurrent);
        this.clock = clock;
    }

    /** 通过则登记一次调用并占用一个并发许可，调用方必须在 finally 里 {@link #release()}；否则抛 429 */
    public void acquire(long accountId) {
        long now = clock.getAsLong();
        Deque<Long> history = calls.computeIfAbsent(accountId, id -> new ArrayDeque<>());
        synchronized (history) {
            while (!history.isEmpty() && now - history.peekFirst() >= DAY_MS) {
                history.pollFirst();
            }
            long lastMinute = history.stream().filter(at -> now - at < MINUTE_MS).count();
            if (lastMinute >= perMinute || history.size() >= perDay) {
                throw new ServiceException(429, "操作太频繁，请稍后再试");
            }
            if (!permits.tryAcquire()) {
                throw new ServiceException(429, "AI 优化繁忙，请稍后再试");
            }
            history.addLast(now);
        }
        if (calls.size() > PURGE_THRESHOLD) {
            calls.entrySet().removeIf(entry -> {
                synchronized (entry.getValue()) {
                    return entry.getValue().isEmpty() || now - entry.getValue().peekLast() >= DAY_MS;
                }
            });
        }
    }

    public void release() {
        permits.release();
    }
}
