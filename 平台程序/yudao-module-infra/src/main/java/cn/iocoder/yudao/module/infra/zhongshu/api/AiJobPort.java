package cn.iocoder.yudao.module.infra.zhongshu.api;

import java.util.List;
import java.util.Optional;

/**
 * AI 任务端口（ai-orchestration 实现）：供 design 模块创建任务与读取候选，
 * 业务模块零互依。
 */
public interface AiJobPort {

    /** 创建平面任务并同事务扣点（幂等键 = user + Idempotency-Key）；返回 jobId */
    long createFlatJob(long userId, int count, String idempotencyKey, String projectRef);

    /** 桥侧公司池建平面任务（不扣个人池；D6 v2）。 */
    long createFlatJobWithOrgCharge(long userId, int count, String idempotencyKey, String projectRef,
                                    long unitPointCost, long totalPointCost);

    /** 桥侧公司池建立面任务（不扣个人池；D6 v2）。 */
    long createElevationJobWithOrgCharge(long userId, int count, String idempotencyKey, String projectRef,
                                         long unitPointCost, long totalPointCost, String resolution);

    /** 创建立面任务并同事务扣点；须引用已选平面任务（projectRef 携带项目上下文） */
    long createElevationJob(long userId, int count, String idempotencyKey, String projectRef);

    default long createFlatJob(long userId, int count, String key, String projectRef, PricingPort.PriceConfirmation price) {
        return createFlatJob(userId, count, key, projectRef);
    }

    default long createFlatJob(long userId, int count, String key, String projectRef,
                               PricingPort.PriceConfirmation price, GenerationImageOptions options) {
        return createFlatJob(userId, count, key, projectRef, price);
    }

    default long createElevationJob(long userId, int count, String key, String projectRef, PricingPort.PriceConfirmation price) {
        return createElevationJob(userId, count, key, projectRef);
    }

    default long createElevationJob(long userId, int count, String key, String projectRef,
                                    PricingPort.PriceConfirmation price, GenerationImageOptions options) {
        return createElevationJob(userId, count, key, projectRef, price);
    }

    Optional<JobView> getJob(long jobId);

    /** 请求取消（QUEUED/RUNNING/VALIDATING → CANCEL_REQUESTED，cancel_seq 单调裁决；终态抛错）。属主校验由实现方负责。 */
    void requestCancellation(long userId, long jobId);

    /** Must share the creation transaction, so a worker never observes a job without its immutable input. */
    default boolean freezeInput(long jobId, java.util.Map<String,Object> snapshot) { throw new UnsupportedOperationException("AI_INPUT_NOT_WIRED"); }

    default Optional<JobView> latestJob(long userId, long projectId, String phase) { return Optional.empty(); }

    /** 已接受的有效候选（仅 ACCEPTED） */
    List<CandidateView> listAcceptedResults(long jobId);

    record JobView(long jobId, long userId, String status, int requestedCount,
                   int acceptedCount, int progress, String phase, String projectRef) {
        public JobView(long jobId,long userId,String status,int requestedCount,int acceptedCount,int progress) {
            this(jobId,userId,status,requestedCount,acceptedCount,progress,null,null);
        }
    }

    record CandidateView(long resultId, int slotNo, String objectKey, String sha256, String mimeType,
                         Integer width, Integer height) {
        public CandidateView(long resultId,int slotNo,String objectKey,String sha256,String mimeType) {
            this(resultId,slotNo,objectKey,sha256,mimeType,null,null);
        }
    }

}
