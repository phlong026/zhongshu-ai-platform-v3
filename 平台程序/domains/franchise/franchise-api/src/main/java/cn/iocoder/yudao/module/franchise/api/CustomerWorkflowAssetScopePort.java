package cn.iocoder.yudao.module.franchise.api;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;

/** A narrow same-company download scope, re-authorized at ticket issue and every byte read. */
public interface CustomerWorkflowAssetScopePort {
    void requireReadable(PostSnapshot post, long customerId, String stepCode, long assetId);
}
