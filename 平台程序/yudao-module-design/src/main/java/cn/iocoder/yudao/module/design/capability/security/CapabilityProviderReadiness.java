package cn.iocoder.yudao.module.design.capability.security;

/**
 * Provider must verify real service billing and durable atomic receipts before enabling the endpoint.
 * Merely finding a bean is not readiness. Implementations throw when either facility is unavailable.
 */
@FunctionalInterface
public interface CapabilityProviderReadiness {
    void verifyReady();
}
