package cn.iocoder.yudao.module.franchise.content.content;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** rules 离线兜底（D-1.5-2）：确定性、零外呼、不编造事实。 */
class ContentRulesRewriteTest {

    @Test
    void keepsFactsAndReorganizesOpening() {
        String rewritten = ContentRulesRewrite.rewrite(
                "乡墅建房要注意地基。地基决定了房子的寿命。其次是结构，框架结构更耐用。", null).text();
        assertThat(rewritten).contains("地基");
        assertThat(rewritten).contains("框架结构");
        assertThat(rewritten).doesNotContain("预测");
    }

    @Test
    void shortFirstSentenceReusedAsOpener() {
        String rewritten = ContentRulesRewrite.rewrite("回乡建房这事儿。后面还有很多细节要讲，比如选址、预算、施工队选择。", null).text();
        assertThat(rewritten).startsWith("回乡建房这事儿。");
    }

    @Test
    void emptySourceYieldsEmpty() {
        assertThat(ContentRulesRewrite.rewrite("", null).text()).isEmpty();
        assertThat(ContentRulesRewrite.rewrite(null, null).text()).isEmpty();
    }

    @Test
    void deterministicAcrossRuns() {
        String source = "同一份原文。产出必须确定。";
        assertThat(ContentRulesRewrite.rewrite(source, null).text())
                .isEqualTo(ContentRulesRewrite.rewrite(source, null).text());
    }

}
