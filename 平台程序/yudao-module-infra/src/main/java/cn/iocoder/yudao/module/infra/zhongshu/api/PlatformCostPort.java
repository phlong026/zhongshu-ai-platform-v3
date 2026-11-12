package cn.iocoder.yudao.module.infra.zhongshu.api;

import java.util.Optional;

/** A 内部成本计量；不操作消费者钱包，不向调用应用发起付款。金额为整数微元（MICRO_CNY）。 */
public interface PlatformCostPort {
    record Subject(String callerAppId, long orgId, long tenantId, long serviceAccountId,
                   String receiptId, String requestKey) { }
    record Preparation(String id, String taskKey, String state, String resultJson) { }
    record Facts(long jobId, long version, String status, boolean terminal, int requestedCount,
                 int validResultCount, long reserved, long charged, long refunded,
                 long promptUnits, long imageUnits, long usageUnits) { }

    void requireReady();
    void registerTaskRouting();
    Preparation prepare(Subject subject, String product, int requestedCount, String fingerprint);
    Optional<Preparation> recover(Subject subject, String fingerprint);
    void requirePrepared(Subject subject, String preparationId, String fingerprint);
    /** 原子建任务，返回已有或新任务 ID；调用前必须已完成业务领域参数与属主校验。 */
    long createJob(long accountId, String taskKey, String phase, int count, String projectRef, String resolution);
    String reservePrompt(long jobId, long token);
    boolean finishPrompt(long jobId, long token, String outcome, String callId, String responseHash, boolean currentAttempt);
    void settleImages(long jobId, int validCount, String status);
    Facts facts(long jobId);
    void requireOwner(Subject subject, long jobId);
    /** 同步调用先持久 DISPATCHED，再在事务外调用；未知结果不得再次 dispatch。 */
    boolean beginExternal(String preparationId);
    /** Only after runtime returned authoritative NOT_FOUND for this same durable receipt key. */
    void authorizeExternalRecovery(String preparationId);
    void saveExternalResult(String preparationId, String resultJson);
    void recordExternalFailure(String preparationId, String receiptEvidence);
    boolean rejectPrepared(Subject subject, String fingerprint);
    void finishUsage(String preparationId, String resultJson);
    /** Settle only a previously received NOTE_POLISH result; never dispatch or require a currently active mapping. */
    void finishRecoveredPolish(Subject subject, String fingerprint, String resultJson);
}
