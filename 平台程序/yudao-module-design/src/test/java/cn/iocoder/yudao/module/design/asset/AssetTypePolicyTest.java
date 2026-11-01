package cn.iocoder.yudao.module.design.asset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯枚举单测：B 端四类策略与 audience/moderationRequired 矩阵（14 文档 D-基1-4/5、AC11）。
 */
class AssetTypePolicyTest {

    @Test
    void bEndTypesHaveExpectedPolicies() {
        assertThat(AssetTypePolicy.FIN_VOUCHER.maxBytes()).isEqualTo(20L * 1024 * 1024);
        assertThat(AssetTypePolicy.FIN_VOUCHER.mimeAllowed("application/pdf")).isTrue();
        assertThat(AssetTypePolicy.FIN_VOUCHER.mimeAllowed("image/png")).isTrue();
        assertThat(AssetTypePolicy.FIN_VOUCHER.keyPrefix()).isEqualTo("fin-vouchers");

        assertThat(AssetTypePolicy.ENG_DRAWING.maxBytes()).isEqualTo(30L * 1024 * 1024);
        assertThat(AssetTypePolicy.ENG_DRAWING.mimeAllowed("application/pdf")).isTrue();
        assertThat(AssetTypePolicy.ENG_DRAWING.keyPrefix()).isEqualTo("eng-drawings");

        assertThat(AssetTypePolicy.ENG_SITE_PHOTO.maxBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(AssetTypePolicy.ENG_SITE_PHOTO.mimeAllowed("application/pdf")).isFalse();
        assertThat(AssetTypePolicy.ENG_SITE_PHOTO.keyPrefix()).isEqualTo("eng-site-photos");

        assertThat(AssetTypePolicy.CONTRACT_ATTACHMENT.mimeAllowed("application/pdf")).isTrue();
        assertThat(AssetTypePolicy.CONTRACT_ATTACHMENT.keyPrefix()).isEqualTo("contract-attachments");
    }

    @Test
    void audienceAndModerationMatrix() {
        // B 端四类：B_END + 免审
        for (AssetTypePolicy type : new AssetTypePolicy[]{AssetTypePolicy.FIN_VOUCHER,
                AssetTypePolicy.ENG_DRAWING, AssetTypePolicy.ENG_SITE_PHOTO, AssetTypePolicy.CONTRACT_ATTACHMENT}) {
            assertThat(type.audience()).as(type.name()).isEqualTo(AssetTypePolicy.Audience.B_END);
            assertThat(type.moderationRequired()).as(type.name()).isFalse();
            assertThat(type.bEnd()).as(type.name()).isTrue();
        }
        // C 端既有六类：C_END + 审核要求（行为零变化，14 文档 AC6）
        for (AssetTypePolicy type : new AssetTypePolicy[]{AssetTypePolicy.USER_SKETCH,
                AssetTypePolicy.DESIGN_REFERENCE, AssetTypePolicy.USER_AVATAR, AssetTypePolicy.CASE_IMAGE,
                AssetTypePolicy.CASE_PDF, AssetTypePolicy.AI_OUTPUT}) {
            assertThat(type.audience()).as(type.name()).isEqualTo(AssetTypePolicy.Audience.C_END);
            assertThat(type.moderationRequired()).as(type.name()).isTrue();
            assertThat(type.bEnd()).as(type.name()).isFalse();
        }
    }

}
