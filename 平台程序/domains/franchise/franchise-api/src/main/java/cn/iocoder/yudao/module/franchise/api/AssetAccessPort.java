package cn.iocoder.yudao.module.franchise.api;

/**
 * B 端资产访问 Port（基-1，14 文档）：franchise 各域以组织服务账号身份走 design 资产票据链，
 * 引用 assetId（零新表，D-基1-1）。类型面=AssetTypePolicy 的 B_END 四类
 * （FIN_VOUCHER/ENG_DRAWING/ENG_SITE_PHOTO/CONTRACT_ATTACHMENT），免内容审核（D-基1-4b）。
 * 实现方：yudao-module-design FranchiseDesignAdapter（同时实现 DesignServicePort，资产能力同源）。
 */
public interface AssetAccessPort {

    /**
     * 签发上传票据（仅接受 B_END 类型，C_END 类型名 → design 资产校验异常）。
     * uploadUrl=COS PUT URL 或 local 占位（local 模式字节经 dev 端点落盘）。
     */
    DesignServicePort.UploadTicketView createUploadTicket(long serviceAccountId, String assetType,
                                                          String mimeType, long sizeBytes, String sha256);

    /** 上传完成：B_END 免审直 ACCEPTED；终态重放幂等读；PENDING/退避对 B_END 不可达 */
    DesignServicePort.AssetCompleted completeUpload(long serviceAccountId, long assetId);

    /** 下载地址：COS=签名 URL+到期；local=local:// 占位（字节核验走 dev 读取端点） */
    DesignServicePort.DownloadUrlView createDownloadUrl(long serviceAccountId, long assetId);

    /**
     * 引用校验：存在 + ACCEPTED + owner==本组织服务账号。
     * 与 {@link DesignServicePort#validateAsset(long, long)} 共享同一校验实现，仅失败语义不同
     * （本方法返回 false 不抛异常；validateAsset 抛 RESOURCE_FORBIDDEN）。消费域引用 assetId 前调用。
     */
    boolean validateReference(long serviceAccountId, long assetId);

}
