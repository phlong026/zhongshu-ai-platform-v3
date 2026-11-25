package cn.iocoder.yudao.module.platform.org;

/** A successfully assembled company account; handled synchronously inside its onboarding transaction. */
public record CompanyAccountRegistered(long tenantId, long orgId) {
}
