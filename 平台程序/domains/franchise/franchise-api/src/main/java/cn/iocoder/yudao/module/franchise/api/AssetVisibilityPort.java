package cn.iocoder.yudao.module.franchise.api;

/** Business-owned visibility in addition to the asset's mandatory tenant/organization boundary. */
public interface AssetVisibilityPort {
    /** Metadata is supplied by the asset service, never by the caller. Existing policies own content assets only. */
    record Resource(long assetId, String assetType, long creatorUserId) { }
    default boolean supports(String assetType) { return "CONTENT_ASSET".equals(assetType); }
    default void requireReadable(long tenantId, AssetAccessPort.Actor actor, Resource resource) {
        requireReadable(tenantId, actor, resource.assetId());
    }
    void requireReadable(long tenantId, AssetAccessPort.Actor actor, long assetId);
}
