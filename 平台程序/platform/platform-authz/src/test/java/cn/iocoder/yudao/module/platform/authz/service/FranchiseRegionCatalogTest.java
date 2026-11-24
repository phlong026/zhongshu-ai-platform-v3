package cn.iocoder.yudao.module.platform.authz.service;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FranchiseRegionCatalogTest {
    // Synthetic administrative fixture: no customer workbook rows or franchise identities.
    private static final List<FranchiseRegionCatalog.Region> REGIONS=List.of(
            region("110000","甲省","province","110000","甲省","",""),
            region("110100","青阳市","city","110000","甲省","110100","青阳市"),
            region("110101","青阳县","district","110000","甲省","110100","青阳市"),
            region("110102","东城区","district","110000","甲省","110100","青阳市"),
            region("110103","桥西区","district","110000","甲省","110100","青阳市"),
            region("110200","海州市","city","110000","甲省","110200","海州市"),
            region("110201","东城区","district","110000","甲省","110200","海州市"),
            region("110202","孟州市","district","110000","甲省","110200","海州市"),
            region("120000","乙省","province","120000","乙省","",""),
            region("120100","南城市","city","120000","乙省","120100","南城市"),
            region("120101","东城区","district","120000","乙省","120100","南城市"),
            region("310000","直辖市","province","310000","直辖市","",""),
            region("310101","中心区","district","310000","直辖市","","直辖市"),
            region("410000","丙省","province","410000","丙省","",""),
            region("419001","直管市","district","410000","丙省","","直管市"));

    @Test
    void matchesFullProvinceCityCountyPathWithAndWithoutHints() {
        for(String province:List.of("","甲省","110000")) {
            var result=resolve(province,"","甲省青阳市东城区");
            assertThat(result.codes()).containsExactly("110102");
            assertThat(result.provinceCode()).isEqualTo("110000");
            assertThat(result.cityCode()).isEqualTo("110100");
            assertThat(result.status()).isEqualTo("RESOLVED");
        }
    }

    @Test
    void cityAndCountySharingStemDoNotLoseTheCountySuffix() {
        assertThat(resolve("甲省","","甲省青阳县").codes()).containsExactly("110101");
        assertThat(resolve("甲省","","甲省青阳东城区").codes()).containsExactly("110102");
        assertThat(resolve("甲省","","甲省青阳").codes()).isEmpty();
    }

    @Test
    void severalCountiesRemainAssociationsOfOneResolutionAndDeduplicateCodes() {
        var result=resolve("甲省","","甲省青阳市东城区、桥西区＋东城区");
        assertThat(result.codes()).containsExactly("110102","110103");
        assertThat(result.status()).isEqualTo("RESOLVED");
    }

    @Test
    void citiesAndBroadAreasStayAtTheirStatedLevel() {
        for(String raw:List.of("甲省青阳市","甲省青阳市（大区）","甲省青阳市市区")) {
            var result=resolve("甲省","",raw);
            assertThat(result.codes()).containsExactly("110100");
            assertThat(result.status()).isEqualTo("PARTIAL");
        }
        assertThat(resolve("","","甲大区").codes()).containsExactly("110000");
        assertThat(resolve("甲省","","甲省青阳市青阳县（大区）").status()).isEqualTo("PARTIAL");
        var mixed=resolve("甲省","","甲省青阳市＋青阳县");
        assertThat(mixed.codes()).containsExactly("110100","110101");
        assertThat(mixed.status()).isEqualTo("PARTIAL");
    }

    @Test
    void developmentZonesAndUnlocatedTownsKeepParentsWithoutGuessingCounty() {
        for(String raw:List.of("甲省青阳市高新技术开发区（一区）","甲省青阳市五福镇")) {
            var result=resolve("甲省","",raw);
            assertThat(result.codes()).isEmpty();
            assertThat(result.provinceCode()).isEqualTo("110000");
            assertThat(result.cityCode()).isEqualTo("110100");
            assertThat(result.status()).isEqualTo("UNRESOLVED");
        }
    }

    @Test
    void explicitCountyWithTownMapsOnlyTheCounty() {
        var result=resolve("甲省","","甲省青阳市青阳县清河镇");
        assertThat(result.codes()).containsExactly("110101");
        assertThat(result.status()).isEqualTo("RESOLVED");
    }

    @Test
    void ambiguousOrMismatchedAdministrativeNamesAreNotSilentlyRewritten() {
        assertThat(resolve("甲省","","甲省东城区").codes()).isEmpty();
        assertThat(resolve("甲省","","甲省海州市孟州县").codes()).isEmpty();
        var partial=resolve("甲省","","甲省海州市东城区、孟州县");
        assertThat(partial.codes()).containsExactly("110201");
        assertThat(partial.status()).isEqualTo("PARTIAL");
    }

    @Test
    void municipalitiesAndDirectAdministeredCountiesDoNotInventCityCodes() {
        var municipality=resolve("直辖市","直辖市","直辖市中心区");
        assertThat(municipality.codes()).containsExactly("310101");
        assertThat(municipality.cityCode()).isEmpty();
        var direct=resolve("丙省","","丙省直管市");
        assertThat(direct.codes()).containsExactly("419001");
        assertThat(direct.cityCode()).isEmpty();
        assertThat(direct.status()).isEqualTo("RESOLVED");
    }

    @Test
    void explicitCodesValidateScopeAndPreserveMixedLevels() {
        var result=FranchiseRegionCatalog.resolve(REGIONS,"甲省","","",List.of("110100","110102","110102"));
        assertThat(result.codes()).containsExactly("110100","110102");
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThatThrownBy(()->FranchiseRegionCatalog.resolve(REGIONS,"乙省","","",List.of("110102")))
                .isInstanceOf(ServiceException.class).hasMessageContaining("省份");
        assertThatThrownBy(()->FranchiseRegionCatalog.resolve(REGIONS,"甲省","海州市","",List.of("110102")))
                .isInstanceOf(ServiceException.class).hasMessageContaining("城市");
        assertThatThrownBy(()->FranchiseRegionCatalog.resolve(REGIONS,"","","",List.of("999999")))
                .isInstanceOf(ServiceException.class).hasMessageContaining("不存在");
    }

    @Test
    void explicitFullPathsAcrossCitiesClearTheSingleCityHint() {
        var result=resolve("甲省","","甲省青阳市桥西区、甲省海州市孟州市");
        assertThat(result.codes()).containsExactly("110103","110202");
        assertThat(result.provinceCode()).isEqualTo("110000");
        assertThat(result.cityCode()).isEmpty();
    }

    @Test
    void invalidExplicitHintsAreRejectedInsteadOfBroadeningTheSearch() {
        assertThatThrownBy(()->resolve("不存在省","","甲省青阳市东城区"))
                .isInstanceOf(ServiceException.class).hasMessageContaining("省份");
        assertThatThrownBy(()->resolve("甲省","不存在市","甲省青阳市东城区"))
                .isInstanceOf(ServiceException.class).hasMessageContaining("城市");
        assertThatThrownBy(()->resolve("甲省","南城市","甲省青阳市东城区"))
                .isInstanceOf(ServiceException.class).hasMessageContaining("省份");
        assertThatThrownBy(()->resolve("丙省","直管市","丙省直管市"))
                .isInstanceOf(ServiceException.class).hasMessageContaining("城市列留空");
        for(String city:List.of("直辖市","直辖","310000")) {
            var result=resolve("",city,"直辖市中心区");
            assertThat(result.codes()).containsExactly("310101");
            assertThat(result.provinceCode()).isEqualTo("310000");
            assertThat(result.cityCode()).isEmpty();
        }
    }

    private static FranchiseRegionCatalog.Resolution resolve(String province,String city,String original) {
        return FranchiseRegionCatalog.resolve(REGIONS,province,city,original,List.of());
    }

    private static FranchiseRegionCatalog.Region region(String code,String name,String level,String pc,String pn,String cc,String cn) {
        boolean district=level.equals("district");
        return new FranchiseRegionCatalog.Region(code,name,level,level.equals("province")?null:cc.isEmpty()?pc:cc,
                pc,pn,cc,cn,district?code:"",district?name:"",district?100.0:null,district?30.0:null);
    }
}
