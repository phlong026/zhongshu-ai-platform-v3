package cn.iocoder.yudao.module.franchise.api;

import java.util.List;

/**
 * 公司积分池端口（24 号任务书 §四）：消费域唯一扣费入口。
 *
 * <p>合同要点：
 * <ul>
 *   <li>金额由服务端按 BillingPriceCatalog 快照换算，**调用方只传计量数量**（次/秒），不传积分数；</li>
 *   <li>reserve 为短事务（锁账户行→幂等→业务权限→公司余额→写 RESERVE），**提交后调用方才可外呼**；</li>
 *   <li>settleByBizKey / releaseByBizKey 幂等：同一 reserve 键重放返回既有终态；无 RESERVE 行时静默 no-op
 *       （兼容上线前已在途的存量任务）；</li>
 *   <li>SUBMISSION_UNCERTAIN 类未知结果**不得调用 release**——保持 PENDING 走人工核对。</li>
 * </ul>
 */
public interface OrgPointPort {

    /** 预扣；返回 operationId。只按业务权限和公司积分余额准入。 */
    long reserve(ReserveCommand command);

    /** 按 operation 结算（按原快照换算实收，退差 max(N-M,0)）。 */
    Settlement settle(long operationId, long actualQuantity);

    /** 按 operation 释放（零交付取消，全额退回）。 */
    Settlement release(long operationId);

    /** 消费域便捷入口：按 RESERVE 的 bizKey 结算（缺失 RESERVE 时 no-op，返回 null）。 */
    Settlement settleByBizKey(String reserveBizKey, long actualQuantity);

    /** 消费域便捷入口：按 RESERVE 的 bizKey 释放（缺失 RESERVE 时 no-op，返回 null）。 */
    Settlement releaseByBizKey(String reserveBizKey);

    record ReserveCommand(String bizKey, long orgId, long actorUserId, Long actorPostId,
                          String subject, long quantity, String refType, Long refId) {
    }

    record Settlement(long operationId, long charged, long refunded, boolean replayed) {
    }

    // ==================== 查询/管理（批 B：老板/员工/总部视图） ====================

    /** 评审 P1-5：补 B-10 要求的"当月全员用量"。 */
    record PoolSummary(long orgId, long balance, long inFlight, String status,
                       String month, long monthUsedTotal) {
    }

    record LedgerEntry(long id, Long operationId, String type, String subject, Long actorUserId,
                       long availableDelta, String billingMonth, String refType, Long refId,
                       String note, java.time.Instant createTime) {
    }

    /** 任务合并视图（服务端分页前折叠）：state=PENDING|COMPLETED|RELEASED。 */
    record OperationView(long operationId, long orgId, String subject, Long actorUserId,
                         String state, long requested, long charged, long refunded,
                         String bizKey, java.time.Instant operationTime, java.time.Instant terminalTime) {
    }

    /** @deprecated 员工月度额度已停用，仅保留类型以兼容旧调用方编译。 */
    @Deprecated(forRemoval = false)
    record MemberQuota(long userId, long monthlyCredits) {
    }

    /** 员工本人当月用量（评审 P1-5：汇总 + 明细分页，替代固定 50 条裸流水）。 */
    /** monthlyQuota 固定为 null；字段仅保留用于响应兼容。 */
    record MyUsage(long orgId, long userId, String month, long used, Long monthlyQuota,
                   List<LedgerEntry> entries) {
    }

    /** 员工业务权限（null = 无行 = 全允许；空列表 = 全禁）。 */
    record MemberPermission(long userId, java.util.List<String> allowedSubjects) {
    }

    PoolSummary summary(long orgId);

    List<LedgerEntry> ledger(long orgId, Long actorUserId, String subject, String billingMonth,
                             int page, int size);

    List<OperationView> operations(long orgId, int page, int size);

    long monthUsed(long orgId, long userId, String billingMonth);

    MyUsage myUsage(long orgId, long userId, String billingMonth, int page, int size);

    /** @deprecated 额度已停用，固定返回 null。 */
    @Deprecated(forRemoval = false)
    Long memberQuotaOf(long orgId, long userId);

    /** @deprecated 额度已停用，固定返回空列表。 */
    @Deprecated(forRemoval = false)
    List<MemberQuota> memberQuotas(long orgId);

    /** @deprecated 额度已停用，调用时抛出稳定业务错误。 */
    @Deprecated(forRemoval = false)
    void setMemberQuota(long orgId, long userId, long monthlyCredits, long operatorUserId);

    /** @deprecated 额度已停用，调用时抛出稳定业务错误。 */
    @Deprecated(forRemoval = false)
    void clearMemberQuota(long orgId, long userId, long operatorUserId);

    /** 员工业务权限清单（无行=全允许）。 */
    java.util.List<MemberPermission> memberPermissions(long orgId);

    /** 设置员工业务权限（空列表=全禁；全开请用 clear）。 */
    void setMemberPermission(long orgId, long userId, java.util.List<String> allowedSubjects,
                             long operatorUserId);

    /** 清除员工业务权限（删行=全允许）。 */
    void clearMemberPermission(long orgId, long userId, long operatorUserId);

    /** 准入校验（免费/缓存路径也须调用；不产生任何扣费）。 */
    void assertSubjectAllowed(long orgId, long userId, String subject);

    // ==================== 设计域接入（D6 v2） ====================

    /** 只算不扣：按目录快照换算积分（创建任务前预览金额）。 */
    long quoteCredits(String subject, long quantity);

    /** 持久化计费归属（BRIDGE/APP），与 reserve 同一事务调用；唯一键 ref_type+ref_id+subject 幂等。 */
    void bindOperation(String origin, long orgId, long actorUserId, Long actorPostId,
                       String refType, long refId, String subject, long operationId, String bizKey);

    /** 异步/结算/恢复按业务引用反查 operation（无绑定返回 null）。 */
    Long findOperationByRef(String refType, long refId, String subject);

    /** 按稳定业务键反查 operation（prompt 逐次调用用，无行返回 null）。 */
    Long findOperationByBizKey(String bizKey);

    /** 设计域计费绑定（org/actor/operation）；无绑定返回 null。 */
    record PoolBinding(long orgId, long actorUserId, Long actorPostId, long operationId) {
    }

    PoolBinding findBinding(String refType, long refId, String subject);

    /** prompt 计费操作（job 维度全链，state=PENDING/SETTLED/RELEASED）。 */
    record PromptOperation(long operationId, String bizKey, String state) {
    }

    java.util.List<PromptOperation> promptOperations(long jobId);

    /** prompt 调用收据（callId/hash/outcome 快照）。 */
    record PromptReceipt(String callId, String responseHash, String outcome, Long operationId) {
    }

    PromptReceipt findPromptReceipt(long jobId, int attemptNo);

    /** 写入/推进收据（DISPATCHED→UNKNOWN/SUCCEEDED、NOT_SENT/UNKNOWN→SUCCEEDED；成功收据不可改）。 */
    void savePromptReceipt(long jobId, int attemptNo, String callId, String responseHash,
                           String outcome, Long operationId);

    /** 开通建池（幂等；已存在不动）。 */
    PoolSummary ensurePool(long orgId);

    /** 开通赠点（仅首次，biz_key=onboard-grant:{orgId}）；返回是否本次实际赠点。 */
    boolean grantOnboardOnce(long orgId, long credits, String note);

    /** 总部调点（biz_key 幂等；负数不得致余额<0）。 */
    PoolSummary adjust(long orgId, long credits, String bizKey, String note, Long adminUserId);
}
