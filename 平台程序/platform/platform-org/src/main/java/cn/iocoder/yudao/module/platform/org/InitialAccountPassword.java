package cn.iocoder.yudao.module.platform.org;

import java.security.SecureRandom;

/** 仅在新建统一用户时生成，明文只交付本次开通响应。 */
public final class InitialAccountPassword {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private InitialAccountPassword() { }

    public static String generate() {
        StringBuilder value = new StringBuilder("Zs");
        value.append((char) ('2' + RANDOM.nextInt(8)));
        while (value.length() < 16) value.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return value.toString();
    }
}
