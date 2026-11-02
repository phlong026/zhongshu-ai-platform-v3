package cn.iocoder.yudao.module.franchise.content.content;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 科目目录与预扣计量（D-1.5-8）：口播秒数=字数÷2 向上取整（对标 oral_budget_units）。 */
class ContentBillingCatalogTest {

    @Test
    void oralSecondsMatchesReferenceFormula() {
        // 对标 billing_catalog.py oral_budget_units: max(1, (len+1)//2)
        assertThat(ContentBillingCatalog.oralSeconds("四五六七")).isEqualTo(2); // (4+1)/2=2
        assertThat(ContentBillingCatalog.oralSeconds("三个字")).isEqualTo(2);   // (3+1)/2=2
        assertThat(ContentBillingCatalog.oralSeconds("两字")).isEqualTo(1);     // (2+1)/2=1
        assertThat(ContentBillingCatalog.oralSeconds("")).isEqualTo(1);
        assertThat(ContentBillingCatalog.oralSeconds(null)).isEqualTo(1);
    }

    @Test
    void estimatedUnitsBySubject() {
        assertThat(ContentBillingCatalog.estimatedUnits("copy", null, null)).isEqualTo(1);
        assertThat(ContentBillingCatalog.estimatedUnits("digital", "0123456789", null)).isEqualTo(5);
        assertThat(ContentBillingCatalog.estimatedUnits("ai_video_768p", null, 8)).isEqualTo(8);
        assertThat(ContentBillingCatalog.estimatedUnits("ai_video_2k", null, 15)).isEqualTo(15);
    }

    @Test
    void unknownSubjectRejected() {
        assertThatThrownBy(() -> ContentBillingCatalog.subject("nonexistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知内容计费科目");
    }

}
