package cn.iocoder.yudao.module.platform.org;

import java.security.SecureRandom;

/** 仅在新建统一用户时生成，明文只交付本次开通响应。 */
public final class InitialAccountPassword {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private InitialAccountPassword() { }

    public static String forStaffLogin(String loginName) {
        if (loginName == null || !loginName.matches("[a-z0-9]{1,61}")) {
            throw new IllegalArgumentException("员工登录名须为不超过61位的小写拼音或数字");
        }
        return loginName + "123";
    }

    public static String generate() {
        StringBuilder value = new StringBuilder("Zs");
        value.append((char) ('2' + RANDOM.nextInt(8)));
        while (value.length() < 16) value.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return value.toString();
    }
}
