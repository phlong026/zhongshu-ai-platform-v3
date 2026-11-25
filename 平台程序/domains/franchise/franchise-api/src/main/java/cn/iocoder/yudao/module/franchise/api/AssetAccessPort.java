package cn.iocoder.yudao.module.franchise.api;

import java.util.Set;

/** 业务附件契约。默认方法仅用于融合模式兼容；平台实现必须使用显式组织及操作者上下文。 */
public interface AssetAccessPort {
    record ContentMetadata(String mimeType, String sha256, long sizeBytes) { }
    default ContentMetadata contentMetadata(Actor actor, long assetId) {
        throw new IllegalArgumentException("内容素材元数据通道尚未配置");
    }
    record Actor(long orgId, long serviceAccountId, long userId, long postId) { }
    record UploadTicketView(long assetId, String uploadUrl) { }
    record AssetCompleted(long assetId, String uploadStatus, String rejectedReason) { }
    record ReferenceContent(byte[] bytes, String mimeType, String sha256) { }
    record ContentMedia(byte[] bytes, String mimeType, String sha256, double durationSeconds, int width, int height) { }

    /** Definitive scanner rejection, distinct from a temporary upload or storage failure. */
    class ContentRejectedException extends RuntimeException {
        public ContentRejectedException(String message) { super(message); }
    }

    /** Internal generated image import. Implementations must apply the ordinary content scanner and ownership checks. */
    default long importContentImage(Actor actor, byte[] bytes, String mimeType) {
        throw new IllegalArgumentException("生成封面保存通道尚未配置");
    }

    /** Clone input: only the caller's accepted CONTENT_ASSET bytes; recheck ownership and accepted digest. */
    default ContentMedia readContentMedia(Actor actor, long assetId) {
        throw new IllegalArgumentException("内容素材读取未配置");
    }

    /** 内部原始读取；必须重新核验当前任职、组织、类型及已审核字节，不能接受外部URL。 */
    default ReferenceContent readDesignReference(Actor actor, long assetId) {
        throw new IllegalArgumentException("参考图读取未配置");
    }

    record DownloadUrlView(String downloadUrl, Long expiresAt) { }

    /** Direct bytes for an independently authorized HQ business reference; never accepts a storage URL or an owner org. */
    default ReferenceContent readHeadquartersDocument(HeadquartersDocumentAccessPort.Request request) {
        throw new IllegalArgumentException("总部资料读取未配置");
    }

    /** Current contract evidence; the ticket never caches customer or contract authorization. */
    default DownloadUrlView createContractDownloadUrl(Actor actor, long contractId, long assetId) {
        throw new IllegalArgumentException("合同凭证访问未配置");
    }

    /** Explicit customer/node evidence scope; never grants generic access to legacy design references. */
    default DownloadUrlView createWorkflowDownloadUrl(Actor actor, long customerId, String stepCode, long assetId) {
        throw new IllegalArgumentException("客户节点资料访问未配置");
    }

    /** 独立 B 的委托成果范围票据；属主从不可变下发清单派生，禁止客户端指定。 */
    default DownloadUrlView createCommissionDownloadUrl(Actor actor, long commissionId, int dispatchSeq,
                                                       CommissionDeliverableAccessPort.Slot slot) {
        throw new IllegalArgumentException("委托成果访问未配置");
    }

    UploadTicketView createUploadTicket(long serviceAccountId, String assetType,
                                       String mimeType, long sizeBytes, String sha256);
    AssetCompleted completeUpload(long serviceAccountId, long assetId);
    DownloadUrlView createDownloadUrl(long serviceAccountId, long assetId);
    String assetTypeOf(long serviceAccountId, long assetId);
    boolean validateReference(long serviceAccountId, long assetId);

    default UploadTicketView createUploadTicket(Actor actor, String assetType, String mimeType,
                                                long sizeBytes, String sha256) {
        return createUploadTicket(actor.serviceAccountId(), assetType, mimeType, sizeBytes, sha256);
    }

    default AssetCompleted completeUpload(Actor actor, long assetId) {
        return completeUpload(actor.serviceAccountId(), assetId);
    }

    default DownloadUrlView createDownloadUrl(Actor actor, long assetId) {
        return createDownloadUrl(actor.serviceAccountId(), assetId);
    }

    default String assetTypeOf(Actor actor, long assetId) {
        return assetTypeOf(actor.serviceAccountId(), assetId);
    }

    default boolean validateReference(Actor actor, long assetId, Set<String> allowedTypes) {
        if (!validateReference(actor.serviceAccountId(), assetId)) return false;
        String type = assetTypeOf(actor, assetId);
        return type != null && allowedTypes.contains(type);
    }

    /** 只有不存在资产记录时才允许检查历史财务文件，拒绝用历史同 ID 覆盖资产类型/状态。 */
    default boolean legacyReferenceAllowed(Actor actor, long assetId) {
        return assetTypeOf(actor, assetId) == null;
    }

    /** orgId 来自已持久化内容任务；实现从本地组织目录恢复租户并核验现有服务映射。无 HTTP 入口。 */
    default DownloadUrlView createTaskDownloadUrl(long orgId, long assetId, String requiredType) {
        throw new IllegalArgumentException("后台素材访问未授权");
    }

    /** 后台内容任务专用；平台实现重新校验组织服务账号映射、租户、类型和 ACCEPTED。 */
    default DownloadUrlView createServiceDownloadUrl(long orgId, long serviceAccountId, long assetId,
                                                     String requiredType) {
        if (!requiredType.equals(assetTypeOf(serviceAccountId, assetId))
                || !validateReference(serviceAccountId, assetId)) {
            throw new IllegalArgumentException("业务素材不可用");
        }
        return createDownloadUrl(serviceAccountId, assetId);
    }
}
