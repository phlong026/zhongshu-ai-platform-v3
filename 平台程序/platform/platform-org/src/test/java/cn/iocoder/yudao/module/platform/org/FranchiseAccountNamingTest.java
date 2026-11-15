package cn.iocoder.yudao.module.platform.org;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FranchiseAccountNamingTest {
    private final FranchiseAccountNaming naming = new FranchiseAccountNaming();

    @Test
    void createsCanonicalNameAndRegionalUsername() {
        var name = naming.describe("合家美宅", "江西省", "南昌市", "高新区", "李忠", null, null);
        assertThat(name.orgName()).isEqualTo("合家美宅-江西南昌-高新-李忠");
        assertThat(name.baseLogin()).isEqualTo("hjmz-jxnc-gaoxin");
        assertThat(name.ownerPinyin()).isEqualTo("lizhong");
        assertThat(name.aliases("hjmz-jxnc-gaoxin-lizhong")).contains(
                "jxnc-gaoxin-lizhong", "lizhong", "lz", "jxnc-gx-lz", "jxnc-gaoxin");
    }

    @Test
    void pinyinOverridesAllowHumanCorrectionOfPolyphonicNames() {
        var name = naming.describe("合家美宅", "重庆市", "重庆市", "江北区", "单重", "jiangbei", "shanchong");
        assertThat(name.ownerPinyin()).isEqualTo("shanchong");
        assertThat(name.baseLogin()).isEqualTo("hjmz-cq-jiangbei");
        assertThat(name.orgName()).isEqualTo("合家美宅-重庆-江北-单重");
        assertThat(name.aliases(name.baseLogin())).contains("sc", "cq-jb-sc");
    }

    @Test
    void validatesNamesAndPinyinInsteadOfSilentlyInventingAliases() {
        assertThatThrownBy(() -> naming.describe("其他品牌", "江西", "南昌", "高新", "李忠", null, null))
                .hasMessageContaining("品牌");
        assertThatThrownBy(() -> naming.describe("合家美宅", "江西", "南昌", "", "李忠", null, null))
                .hasMessageContaining("片区");
        assertThatThrownBy(() -> naming.describe("合家美宅", "江西", "南昌", "高新", "李忠", "gao-xin", null))
                .hasMessageContaining("拼音");
        assertThatThrownBy(() -> naming.describe("合家美宅", "江西", "南昌", "高新", "李-忠", null, null))
                .hasMessageContaining("姓名");
    }

    @Test
    void usesStableProvinceAndCityCodesInsteadOfPolyphonicInitials() {
        assertThat(naming.describe("合家美宅", "陕西省", "西安市", "高新", "张三", null, null).baseLogin())
                .isEqualTo("hjmz-sxxa-gaoxin");
        assertThat(naming.describe("合家美宅", "吉林省", "长春市", "高新", "张三", null, null).baseLogin())
                .isEqualTo("hjmz-jlcc-gaoxin");
    }

    @Test
    void englishNamesProduceUniqueCaseNormalizedAliases() {
        var name = naming.describe("合家美宅", "江西", "南昌", "高新", "LiZhong", null, null);
        assertThat(name.aliases(name.baseLogin())).doesNotHaveDuplicates().contains("lizhong");
    }

    @Test
    void manualPinyinAlsoSupportsCharactersOutsideTheBundledDictionary() {
        var name = naming.describe("合家美宅", "江西", "南昌", "高新", "𠮷忠", null, "jizhong");
        assertThat(name.aliases(name.baseLogin())).contains("jizhong", "jz");
    }
}
