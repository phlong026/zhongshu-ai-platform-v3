package cn.iocoder.yudao.module.platform.identity;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 完整账号登录、旧检索和首次改密的公共输入规则。 */
public final class AccountPasswordRules {

    private AccountPasswordRules() {
    }

    public static String normalizeQuery(String value) {
        String normalized = normalizeLoginName(value);
        return normalized == null || normalized.length() < 2 ? null : normalized;
    }

    public static String normalizeLoginName(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 100
                || !normalized.matches("[\\p{L}\\p{N}-]+")) return null;
        return normalized;
    }

    public static boolean validNewPassword(String value) {
        return value != null && value.length() >= 6 && value.length() <= 64 && validBcryptInput(value);
    }

    public static boolean validBcryptInput(String value) {
        return value != null && value.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
