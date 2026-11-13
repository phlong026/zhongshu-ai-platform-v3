package cn.iocoder.yudao.module.commerce.orgpoint;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 批 A 价格目录验收（24 号 §七）：科目全覆盖、秒→分向上取整、次=1、未知科目拒绝。 */
class BillingPriceCatalogTest {

    @Test
    void allContentAndDesignSubjectsRegistered() {
        for (String key : new String[]{"link_resolution", "copy", "asr", "digital",
                "ai_video_768p", "ai_video_2k", "ai_flat", "ai_elevation",
                "budget_estimate", "note_polish"}) {
            assertThat(BillingPriceCatalog.contains(key)).as(key).isTrue();
        }
    }

    @Test
    void callSubjectsChargeMinOne() {
        assertThat(BillingPriceCatalog.creditsFor(BillingPriceCatalog.LINK_RESOLUTION, 1)).isEqualTo(1);
        assertThat(BillingPriceCatalog.creditsFor(BillingPriceCatalog.COPY, 1)).isEqualTo(2);
        assertThat(BillingPriceCatalog.creditsFor(BillingPriceCatalog.AI_ELEVATION, 1)).isEqualTo(3);
    }

    @Test
    void secondSubjectsRoundUp() {
        // digital 50_000 微积分/秒：1 秒 → 1 积分（向上取整）；60 秒 → 3；300 秒 → 15
        assertThat(BillingPriceCatalog.creditsFor(BillingPriceCatalog.DIGITAL, 1)).isEqualTo(1);
        assertThat(BillingPriceCatalog.creditsFor(BillingPriceCatalog.DIGITAL, 60)).isEqualTo(3);
        assertThat(BillingPriceCatalog.creditsFor(BillingPriceCatalog.DIGITAL, 300)).isEqualTo(15);
        // 768P 10 积分/分钟；2K 20 积分/分钟
        assertThat(BillingPriceCatalog.creditsFor(BillingPriceCatalog.AI_VIDEO_768P, 60)).isEqualTo(10);
        assertThat(BillingPriceCatalog.creditsFor(BillingPriceCatalog.AI_VIDEO_2K, 60)).isEqualTo(20);
    }

    @Test
    void unknownSubjectRejected() {
        assertThatThrownBy(() -> BillingPriceCatalog.subject("no_such_subject"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
