package cn.iocoder.yudao.module.franchise.api;

/** Business-owned visibility in addition to the asset's mandatory tenant/organization boundary. */
public interface AssetVisibilityPort {
    void requireReadable(long tenantId, AssetAccessPort.Actor actor, long assetId);
}
