package cn.iocoder.yudao.module.franchise.crm;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CrmRegionsTest {
    static CrmAddress address() {
        var province=CrmRegions.children("").stream().filter(row->row.code().equals("42")).findFirst().orElseThrow();
        var city=CrmRegions.children(province.code()).get(0);
        var district=CrmRegions.children(city.code()).get(0);
        var town=CrmRegions.children(district.code()).get(0);
        var village=CrmRegions.children(town.code()).get(0);
        return CrmAddress.normalize(province.name(),city.name(),district.name(),town.name(),village.name(),village.code(),"一组12号");
    }

    @Test void catalogReachesVillageAndRejectsMixedParentNames() {
        var address=address();
        assertThat(address.regionCode()).hasSize(12);
        assertThat(CrmRegions.children(address.regionCode().substring(0,9)))
                .anyMatch(row->row.code().equals(address.regionCode())&&row.name().equals(address.village()));
        assertThatThrownBy(()->CrmAddress.normalize("广东省",address.city(),address.district(),address.town(),
                address.village(),address.regionCode(),address.addressDetail())).hasMessageContaining("层级不匹配");
        assertThatThrownBy(()->CrmAddress.normalize(address.province(),address.city(),address.district(),address.town(),
                address.village(),address.regionCode().substring(0,9),null)).hasMessageContaining("层级不匹配");
    }

    @Test void invalidParentsCannotReadArbitraryResources() {
        assertThat(CrmRegions.children("../../root")).isEmpty();
        assertThat(CrmRegions.children("999999999")).isEmpty();
        assertThat(CrmAddress.normalize("湖北省","武汉市",null,null,null,null,null).province()).isEqualTo("湖北省");
        assertThatThrownBy(()->CrmAddress.normalize("湖北省","武汉市","江夏区",null,null,null,null))
                .hasMessageContaining("完整选择");
    }
}
