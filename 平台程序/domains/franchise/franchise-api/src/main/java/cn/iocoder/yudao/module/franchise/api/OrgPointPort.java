package cn.iocoder.yudao.module.franchise.api;

/**
 * 公司积分池端口（24 号任务书 §四）：消费域唯一扣费入口。
 *
 * <p>合同要点：
 * <ul>
 *   <li>金额由服务端按 BillingPriceCatalog 快照换算，**调用方只传计量数量**（次/秒），不传积分数；</li>
 *   <li>reserve 为短事务（锁账户行→幂等→额度→余额→写 RESERVE），**提交后调用方才可外呼**；</li>
 *   <li>settleByBizKey / releaseByBizKey 幂等：同一 reserve 键重放返回既有终态；无 RESERVE 行时静默 no-op
 *       （兼容上线前已在途的存量任务）；</li>
 *   <li>SUBMISSION_UNCERTAIN 类未知结果**不得调用 release**——保持 PENDING 走人工核对。</li>
 * </ul>
 */
public interface OrgPointPort {

    /** 预扣；返回 operationId。额度/余额不足抛 1_072 段错误码。 */
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
}
