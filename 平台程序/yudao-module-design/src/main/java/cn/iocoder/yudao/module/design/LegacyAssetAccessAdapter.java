package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 融合模式的旧资产桥接；业务资产 DTO 与设计能力 DTO 分别演进。 */
@Component
@ConditionalOnProperty(prefix = "platform.legacy-design-bridge", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class LegacyAssetAccessAdapter implements AssetAccessPort {

    private final FranchiseDesignAdapter design;
    private final StoreServiceAccountPort serviceAccounts;

    public LegacyAssetAccessAdapter(FranchiseDesignAdapter design, StoreServiceAccountPort serviceAccounts) {
        this.design = design;
        this.serviceAccounts = serviceAccounts;
    }

    @Override
    public UploadTicketView createUploadTicket(long serviceAccountId, String assetType,
                                               String mimeType, long sizeBytes, String sha256) {
        // 五参数入口保留 B_END 类型权限，不能改用设计 command 重载的 C_END 通道。
        var ticket = design.createUploadTicket(serviceAccountId, assetType, mimeType, sizeBytes, sha256);
        return new UploadTicketView(ticket.assetId(), ticket.uploadUrl());
    }

    @Override
    public AssetCompleted completeUpload(long serviceAccountId, long assetId) {
        var result = design.completeUpload(serviceAccountId, assetId);
        return new AssetCompleted(result.assetId(), result.uploadStatus(), result.rejectedReason());
    }

    @Override
    public DownloadUrlView createDownloadUrl(long serviceAccountId, long assetId) {
        var result = design.createDownloadUrl(serviceAccountId, assetId);
        return new DownloadUrlView(result.downloadUrl(), result.expiresAt());
    }

    @Override
    public DownloadUrlView createTaskDownloadUrl(long orgId, long assetId, String requiredType) {
        // orgId 由已持久化任务提供；沿用融合模式账号解析和资产类型、属主、审核状态校验。
        long serviceAccountId = serviceAccounts.requireServiceAccount(orgId);
        return createServiceDownloadUrl(orgId, serviceAccountId, assetId, requiredType);
    }

    @Override
    public String assetTypeOf(long serviceAccountId, long assetId) {
        return design.assetTypeOf(serviceAccountId, assetId);
    }

    @Override
    public boolean validateReference(long serviceAccountId, long assetId) {
        return design.validateReference(serviceAccountId, assetId);
    }

    @Override
    public boolean legacyReferenceAllowed(Actor actor, long assetId) {
        return design.legacyReferenceAllowed(assetId);
    }
}
