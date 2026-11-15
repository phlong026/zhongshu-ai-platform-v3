package cn.iocoder.yudao.module.platform.identity;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 账号检索与首次改密的公共输入规则。 */
public final class AccountPasswordRules {

    private AccountPasswordRules() {
    }

    public static String normalizeQuery(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() < 2 || normalized.length() > 100
                || !normalized.matches("[\\p{L}\\p{N}-]+")) return null;
        return normalized;
    }

    public static boolean validNewPassword(String value) {
        if (value == null || value.length() < 10 || value.length() > 64
                || !validBcryptInput(value)) return false;
        return value.chars().anyMatch(c -> c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z')
                && value.chars().anyMatch(c -> c >= '0' && c <= '9');
    }

    public static boolean validBcryptInput(String value) {
        return value != null && value.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
