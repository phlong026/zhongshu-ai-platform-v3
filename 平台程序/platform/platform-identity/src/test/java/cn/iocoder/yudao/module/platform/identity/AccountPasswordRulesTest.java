package cn.iocoder.yudao.module.platform.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountPasswordRulesTest {

    @Test
    void acceptsTenToSixtyFourCharactersWithLettersAndDigits() {
        assertThat(AccountPasswordRules.validNewPassword("NewSecret2026")).isTrue();
        assertThat(AccountPasswordRules.validNewPassword("中文Password123")).isTrue();
    }

    @Test
    void rejectsWeakOversizedAndBcryptTruncatedPasswords() {
        assertThat(AccountPasswordRules.validNewPassword("short12")).isFalse();
        assertThat(AccountPasswordRules.validNewPassword("onlylettersxx")).isFalse();
        assertThat(AccountPasswordRules.validNewPassword("1234567890")).isFalse();
        assertThat(AccountPasswordRules.validNewPassword("只有中文密码123456")).isFalse();
        assertThat(AccountPasswordRules.validNewPassword("中".repeat(25) + "a1")).isFalse();
        assertThat(AccountPasswordRules.validNewPassword("a1" + "x".repeat(63))).isFalse();
        assertThat(AccountPasswordRules.validBcryptInput("中".repeat(25))).isFalse();
    }

    @Test
    void normalizesOnlySupportedAccountSearchCharacters() {
        assertThat(AccountPasswordRules.normalizeQuery("  JXNC-GaoXin-LiZhong  "))
                .isEqualTo("jxnc-gaoxin-lizhong");
        assertThat(AccountPasswordRules.normalizeQuery("李忠")).isEqualTo("李忠");
        assertThat(AccountPasswordRules.normalizeQuery("l")).isNull();
        assertThat(AccountPasswordRules.normalizeQuery("li_zhong")).isNull();
        assertThat(AccountPasswordRules.normalizeQuery("")).isNull();
    }
}
