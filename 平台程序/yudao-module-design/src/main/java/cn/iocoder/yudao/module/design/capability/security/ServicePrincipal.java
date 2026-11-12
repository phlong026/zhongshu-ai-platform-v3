package cn.iocoder.yudao.module.design.capability.security;

import cn.iocoder.yudao.module.franchise.api.capability.security.CapabilityKeyring;

/** Service identity resolved by A; it is deliberately not a human LoginUser. */
public record ServicePrincipal(String callerAppId, long orgId, long tenantId, long serviceAccountId) {
    public ServicePrincipal {
        CapabilityKeyring.requireToken(callerAppId, 80);
        if (orgId <= 0 || tenantId <= 0 || serviceAccountId <= 0) {
            throw new IllegalArgumentException("Mapped service identity requires positive IDs");
        }
    }
}
