package cn.iocoder.yudao.module.platform.identity;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.LOGIN_CONSENT_REQUIRED;

/** 登录前由服务端发布当前协议版本，两种登录方式使用同一校验。 */
public final class LoginPolicy {
    private LoginPolicy() { }
    public record Policy(String agreementVersion, String privacyVersion, int passwordMinLength, int passwordMaxLength) { }
    public record Consent(Boolean accepted, String agreementVersion, String privacyVersion) { }
    public static Policy current() { return new Policy("2026-11-15", "2026-11-15", 6, 64); }
    public static void require(Consent consent, Integer loginVersion) {
        // 已上线旧版使用底部勾选，继续兼容其原请求；新版两条通道必须提交明确同意及版本。
        if (consent == null && loginVersion == null) return;
        Policy policy = current();
        if (consent == null || !Boolean.TRUE.equals(consent.accepted())
                || !policy.agreementVersion().equals(consent.agreementVersion())
                || !policy.privacyVersion().equals(consent.privacyVersion())) throw exception(LOGIN_CONSENT_REQUIRED);
    }
}
