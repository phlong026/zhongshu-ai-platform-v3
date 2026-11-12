package cn.iocoder.yudao.module.franchise.api;

import java.util.Set;

/** 业务附件契约。默认方法仅用于融合模式兼容；平台实现必须使用显式组织及操作者上下文。 */
public interface AssetAccessPort {
    record Actor(long orgId, long serviceAccountId, long userId, long postId) { }
    record UploadTicketView(long assetId, String uploadUrl) { }
    record AssetCompleted(long assetId, String uploadStatus, String rejectedReason) { }
    record DownloadUrlView(String downloadUrl, Long expiresAt) { }

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
