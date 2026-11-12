package cn.iocoder.yudao.module.design.capability.security;

import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract;

/** A-owned caller/org mapping. The legacy resolve contract requires an active, authorized mapping. */
@FunctionalInterface
public interface CallerOrganizationResolver {
    ServicePrincipal resolve(String callerAppId, long orgId);

    /**
     * Called only after strict parsing, signature/time/operation validation and nonce acceptance.
     * Overrides may resolve durable receipt scope for QUERY_RECEIPT and GET_SETTLEMENT;
     * all other operations retain the active-mapping policy. The default preserves legacy behavior.
     */
    default ServicePrincipal resolveRequest(DesignCapabilityContract.Request<?> request) {
        return resolve(request.callerAppId(), request.orgId());
    }
}
