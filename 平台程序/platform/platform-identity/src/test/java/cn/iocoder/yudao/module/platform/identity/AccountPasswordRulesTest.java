package cn.iocoder.yudao.module.platform.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountPasswordRulesTest {

    @Test
    void acceptsSixToSixtyFourCharactersWithoutCompositionRequirements() {
        for (String password : new String[]{"123456", "abcdef", "abc123", "lili123", "lili2123", "a".repeat(64),
                "NewSecret2026", "中文Password123", "只有中文密码123456"}) {
            assertThat(AccountPasswordRules.validNewPassword(password)).as(password).isTrue();
        }
    }

    @Test
    void rejectsPasswordsOutsideLengthAndBcryptLimits() {
        for (String password : new String[]{null, "", "12345", "a".repeat(65), "中".repeat(25) + "a1"}) {
            assertThat(AccountPasswordRules.validNewPassword(password)).as(password).isFalse();
        }
        assertThat(AccountPasswordRules.validNewPassword("中".repeat(24))).isTrue();
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
