package cn.iocoder.yudao.module.franchise.api;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;

/** Revalidates a contract's current file reference and the current customer's visibility. */
public interface ContractDocumentAccessPort {
    void requireReadable(PostSnapshot post, long contractId, long assetId);
    /** Applies the same customer ACL to generic downloads of any current or historical contract reference. */
    void requireAssetReadable(PostSnapshot post, long assetId);
}
