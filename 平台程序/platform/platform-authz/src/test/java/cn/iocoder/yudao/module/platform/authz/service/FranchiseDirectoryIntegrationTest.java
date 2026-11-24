package cn.iocoder.yudao.module.platform.authz.service;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.authz.FakeSystemDirectoryForOnboard;
import cn.iocoder.yudao.module.platform.org.FranchiseAccountNaming;
import cn.iocoder.yudao.module.platform.org.OrgServiceImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostServiceImpl;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

@Import({OrgServiceImpl.class,StaffPostServiceImpl.class,FranchiseAccountNaming.class,FranchiseOnboardService.class,
        FranchiseRegionCatalog.class,FranchiseDirectoryService.class,FranchiseMapService.class,FranchiseDirectoryIntegrationTest.Config.class})
class FranchiseDirectoryIntegrationTest extends PlatformPgIntegrationTest {
    @TestConfiguration static class Config {
        @Bean Directory fakeDirectory(JdbcTemplate jdbc){return new Directory(jdbc);}
        @Bean SelectedPost selectedPost(){return new SelectedPost();}
    }
    static class SelectedPost implements CurrentPost {
        PostSnapshot value;
        public Optional<PostSnapshot> find(){return Optional.ofNullable(value);}
    }
    static class Directory extends FakeSystemDirectoryForOnboard {
        private final JdbcTemplate jdbc;
        Directory(JdbcTemplate jdbc){this.jdbc=jdbc;}
        @Override public long createDept(Long parent,String name) {
            long tenant=TenantContextHolder.getRequiredTenantId();
            long id=super.createDept(parent,tenant+":"+name);
            jdbc.update("INSERT INTO system_dept(id,name,parent_id,tenant_id,status,deleted) VALUES(?,?,?,?,0,0)",id,name,parent==null?0:parent,tenant);
            return id;
        }
        @Override public List<Long> findRootDeptIdsByName(String name){return super.findRootDeptIdsByName(TenantContextHolder.getRequiredTenantId()+":"+name);}
        @Override public long createUserWithPassword(String name,String mobile,Long dept,String password){return createUser(name,mobile,dept);}
    }
    @Resource private FranchiseDirectoryService directory;
    @Resource private FranchiseRegionCatalog regions;
    @Resource private FranchiseMapService maps;
    @Resource private FranchiseOnboardService onboard;
    @Resource private Directory system;
    @Resource private SelectedPost selected;
    @Resource private JdbcTemplate jdbc;
    private static final AtomicLong IDS=new AtomicLong(81000);
    private long tenant;

    @BeforeEach void setup() {
        tenant=IDS.incrementAndGet();TenantContextHolder.setTenantId(tenant);headquarters();
        jdbc.update("INSERT INTO pf_org_type(code,domain,name) VALUES('store','test','门店') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO pf_post_template(role_code,domain,org_types,lead) VALUES('boss','test','store,headquarters',false) ON CONFLICT(role_code) DO UPDATE SET org_types='store,headquarters'");
        system.roleIdsByCode.put("boss",501L);
    }
    @AfterEach void clear(){TenantContextHolder.clear();selected.value=null;}
    private void headquarters(){selected.value=new PostSnapshot(1,7,999,"headquarters","sales",null,false,Set.of());}
    private FranchiseDirectoryWorkbook.Row row(String key,String brand,String raw,List<String> codes) {
        return new FranchiseDirectoryWorkbook.Row(key,3,"Synthetic","C3",brand,brand+" · "+raw,"湖北省","武汉市",raw,"COVERAGE",codes,"");
    }
    private FranchiseDirectoryService.Item create(String raw,List<String> codes) {
        return directory.create(new FranchiseDirectoryService.RecordRequest("测试覆盖资料","测试甲","COVERAGE",raw,codes,"420000","420100",null),7);
    }
    @SuppressWarnings("unchecked") private List<FranchiseMapService.MapRecord> records(Map<String,Object> snapshot){return (List<FranchiseMapService.MapRecord>)snapshot.get("records");}
    @SuppressWarnings("unchecked") private List<FranchiseMapService.Point> points(Map<String,Object> snapshot){return (List<FranchiseMapService.Point>)snapshot.get("points");}
    @SuppressWarnings("unchecked") private List<FranchiseMapService.Brand> brands(Map<String,Object> snapshot){return (List<FranchiseMapService.Brand>)snapshot.get("brands");}

    @Test void precheckDoesNotWriteAndRepeatedSubmissionKeepsOneSourceRecordWithTwoDistricts() {
        var input=row("source:sheet1:C3","测试甲","黄陂区、新洲区",List.of("420116","420117"));
        var preview=directory.importRows(List.of(input),true,7);
        assertThat(preview.created()).isEqualTo(1);assertThat(preview.dryRun()).isTrue();
        assertThat(directory.page("",null,null,1,20).getTotal()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_map_brand WHERE tenant_id=?",Long.class,tenant)).isZero();
        var written=directory.importRows(List.of(input),false,7);
        assertThat(written.created()).isEqualTo(1);String id=written.rows().get(0).recordId();
        assertThat(directory.get(id).regionCodes()).containsExactly("420116","420117");
        assertThat(directory.importRows(List.of(input),false,7).skipped()).isEqualTo(1);
        var map=maps.snapshot();assertThat(map.get("schemaVersion")).isEqualTo(2);
        assertThat(records(map)).hasSize(1);assertThat(records(map).get(0).recordType()).isEqualTo("COVERAGE");
        assertThat(points(map)).hasSize(2).extracting(FranchiseMapService.Point::recordId).containsOnly(id);
        assertThat((List<?>)map.get("stores")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_franchise_account WHERE tenant_id=?",Long.class,tenant)).isZero();
    }

    @Test void invalidRowsDoNotDropGoodRowsAndDifferentCellsOnTheSameSpreadsheetRowStayDistinct() {
        var first=row("book:s1:C3","测试甲","黄陂区",List.of("420116"));
        var second=row("book:s1:H3","测试乙","黄陂区",List.of("420116"));
        var invalid=row("book:s1:M3","测试丙","",List.of());
        var result=directory.importRows(List.of(first,invalid,second),false,7);
        assertThat(result.total()).isEqualTo(3);assertThat(result.created()).isEqualTo(2);assertThat(result.failed()).isEqualTo(1);
        assertThat(directory.page("",null,null,1,20).getTotal()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_map_brand WHERE tenant_id=?",Long.class,tenant)).isEqualTo(2);
        var collision=directory.importRows(List.of(first,first),true,7);
        assertThat(collision.skipped()).isEqualTo(1);assertThat(collision.failed()).isEqualTo(1);
    }

    @Test void cityAndUnknownCommercialAreasRemainPartialWithoutPretendDistrictPoints() {
        var result=directory.importRows(List.of(
                row("city","测试甲","武汉市",List.of("420100")),
                row("commercial","测试甲","武汉光谷开发区",List.of())),false,7);
        assertThat(result.created()).isEqualTo(2);assertThat(result.unresolved()).isEqualTo(2);
        var city=directory.get(result.rows().get(0).recordId());
        assertThat(city.matchStatus()).isEqualTo("PARTIAL");assertThat(city.regionCodes()).containsExactly("420100");
        var unresolved=directory.get(result.rows().get(1).recordId());
        assertThat(unresolved.cityCode()).isEqualTo("420100");assertThat(unresolved.matchStatus()).isEqualTo("UNRESOLVED");
        assertThat(points(maps.snapshot())).isEmpty();
        assertThat(regions.children("420100")).extracting(FranchiseRegionCatalog.Region::code).contains("420116","420117","420115");
        assertThat(regions.all()).extracting(FranchiseRegionCatalog.Region::code).doesNotHaveDuplicates();
        assertThat(regions.children("110000")).filteredOn(r->r.level().equals("district")).extracting(FranchiseRegionCatalog.Region::code).contains("110101");
        assertThat(regions.all()).filteredOn(r->r.code().equals("110101")).allMatch(r->r.cityCode().isEmpty());
    }

    @Test void allValidHeadquartersRolesCanReadAllBrandsWhileStoreStaffRemainBrandScoped() {
        directory.importRows(List.of(row("a","测试甲","黄陂区",List.of("420116")),row("b","测试乙","新洲区",List.of("420117"))),false,7);
        var opened=onboard.onboardWithDistrict("测试甲","湖北省","武汉市","黄陂区","测试老板","13900001231",null,null,"",7,"420116");
        assertThat(system.roleHasPermission("sales",FranchiseMapService.READ_ALL)).isFalse();
        assertThat(records(maps.snapshot())).hasSize(3).extracting(FranchiseMapService.MapRecord::brandName).contains("测试甲","测试乙");
        selected.value=new PostSnapshot(2,7,opened.orgId(),"store","finance",null,false,Set.of());
        var own=maps.snapshot();assertThat(own.get("scope")).isEqualTo("brand");
        assertThat(records(own)).hasSize(2).allMatch(r->r.brandName().equals("测试甲"));
        headquarters();jdbc.update("UPDATE pf_org_unit SET status='DISABLED' WHERE dept_id=?",opened.orgId());
        assertThat(records(maps.snapshot())).hasSize(2);
        selected.value=null;assertThatThrownBy(()->maps.snapshot()).isInstanceOf(ServiceException.class);
    }

    @Test void importsAndDirectoryReadsRemainTenantScoped() {
        var result=directory.importRows(List.of(row("shared-key","测试甲","黄陂区",List.of("420116"))),false,7);
        String id=result.rows().get(0).recordId();
        TenantContextHolder.setTenantId(tenant+100000);
        assertThatThrownBy(()->directory.get(id)).isInstanceOf(ServiceException.class);
        assertThat(records(maps.snapshot())).isEmpty();
        assertThat(directory.importRows(List.of(row("shared-key","测试乙","新洲区",List.of("420117"))),false,7).created()).isEqualTo(1);
        TenantContextHolder.setTenantId(tenant);
        assertThat(records(maps.snapshot())).hasSize(1).allMatch(r->r.brandName().equals("测试甲"));
    }

    @Test void brandsWithNoActiveRecordsRemainVisibleWithinTheReadersScope() {
        jdbc.update("INSERT INTO pf_map_brand(tenant_id,name) VALUES(?,?)",tenant,"零资料品牌");
        assertThat(records(maps.snapshot())).isEmpty();
        assertThat(brands(maps.snapshot())).extracting(FranchiseMapService.Brand::name).containsExactly("零资料品牌");
        var opened=onboard.onboardWithDistrict("测试甲","湖北省","武汉市","黄陂区","测试老板","13900001237",null,null,"",7,"420116");
        jdbc.update("UPDATE pf_franchise_directory SET active=false WHERE tenant_id=?",tenant);
        selected.value=new PostSnapshot(2,7,opened.orgId(),"store","sales",null,false,Set.of());
        var own=maps.snapshot();
        assertThat(records(own)).isEmpty();
        assertThat(brands(own)).extracting(FranchiseMapService.Brand::name).containsExactly("测试甲");
    }

    @Test void bindingRequiresFreshConfirmedDistrictAndRetainsOriginalSourceAndMultiCountyRelationship() {
        var item=create("黄陂区、新洲区",List.of("420116","420117"));
        var stale=new FranchiseDirectoryService.FromDirectoryRequest(item.id(),item.version()+1,"测试老板","13900001232",null,null,"","420116");
        assertThatThrownBy(()->directory.onboard(stale,7)).isInstanceOf(ServiceException.class).hasMessageContaining("已修改");
        var missingDistrict=new FranchiseDirectoryService.FromDirectoryRequest(item.id(),item.version(),"测试老板","13900001232",null,null,"",null);
        assertThatThrownBy(()->directory.onboard(missingDistrict,7)).isInstanceOf(ServiceException.class).hasMessageContaining("多个区县");
        var request=new FranchiseDirectoryService.FromDirectoryRequest(item.id(),item.version(),"测试老板","13900001232",null,null,"","420116");
        var opened=directory.onboard(request,7);var linked=directory.get(item.id());
        assertThat(linked.linkedOrgId()).isEqualTo(Long.toString(opened.orgId()));assertThat(linked.recordType()).isEqualTo("FRANCHISE");
        assertThat(linked.sourceKey()).isEqualTo(item.sourceKey());assertThat(linked.originalRegionText()).isEqualTo(item.originalRegionText());
        assertThat(linked.regionCodes()).containsExactly("420116","420117");
        assertThat(records(maps.snapshot())).hasSize(1);assertThat(points(maps.snapshot())).hasSize(2);
        assertThatThrownBy(()->directory.onboard(request,7)).isInstanceOf(ServiceException.class);
        jdbc.update("UPDATE pf_franchise_account SET org_name=? WHERE account_id=?","更正门店名称",opened.accountId());
        assertThat(directory.get(item.id()).regionCodes()).containsExactly("420116","420117");
        assertThat(directory.get(item.id()).name()).isEqualTo("更正门店名称");
        jdbc.update("UPDATE pf_franchise_account SET area='新洲区',map_district_code='420117' WHERE account_id=?",opened.accountId());
        assertThat(directory.get(item.id()).regionCodes()).containsExactly("420116","420117");
        assertThat(points(maps.snapshot())).hasSize(2);
        jdbc.update("UPDATE pf_franchise_account SET province='湖北' WHERE account_id=?",opened.accountId());
        assertThat(directory.get(item.id()).regionCodes()).containsExactly("420116","420117");
        assertThat(directory.get(item.id()).originalRegionText()).isEqualTo(item.originalRegionText());
        var normalized=directory.get(item.id());
        var renamed=directory.update(normalized.id(),new FranchiseDirectoryService.RecordRequest("规范后的名称",normalized.brand(),"FRANCHISE",normalized.originalRegionText(),normalized.regionCodes(),normalized.provinceCode(),normalized.cityCode(),normalized.version()),7);
        assertThat(renamed.regionCodes()).containsExactly("420116","420117");
    }

    @Test void broadCitySourceCannotOpenAnAccountAndDoesNotCreateAccountSideEffects() {
        var item=create("武汉市",List.of("420100"));
        assertThatThrownBy(()->directory.onboard(new FranchiseDirectoryService.FromDirectoryRequest(item.id(),item.version(),"测试老板","13900001233",null,null,"","420116"),7))
                .isInstanceOf(ServiceException.class).hasMessageContaining("具体区县");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_franchise_account WHERE tenant_id=?",Long.class,tenant)).isZero();
    }

    @Test void replayingTheOriginalImportAfterOpeningSkipsWithoutDowngradingTheFranchise() {
        var source=row("stable:source:1","测试甲","黄陂区、新洲区",List.of("420116","420117"));
        var imported=directory.importRows(List.of(source),false,7);
        var item=directory.get(imported.rows().get(0).recordId());
        directory.onboard(new FranchiseDirectoryService.FromDirectoryRequest(item.id(),item.version(),"测试老板","13900001239",null,null,"","420116"),7);
        var opened=directory.get(item.id());
        for(boolean dryRun:List.of(true,false)) {
            var replay=directory.importRows(List.of(source),dryRun,7);
            assertThat(replay.skipped()).isEqualTo(1);
            assertThat(replay.rows().get(0).recordType()).isEqualTo("FRANCHISE");
            assertThat(replay.rows().get(0).name()).isEqualTo(opened.name());
        }
        var preserved=directory.get(item.id());
        assertThat(preserved).isEqualTo(opened);
        assertThat(preserved.recordType()).isEqualTo("FRANCHISE");
        assertThat(preserved.regionCodes()).containsExactly("420116","420117");
        var changed=row(source.sourceKey(),"测试甲","新洲区",List.of("420117"));
        var rejected=directory.importRows(List.of(changed),false,7);
        assertThat(rejected.failed()).isEqualTo(1);
        assertThat(rejected.rows().get(0).message()).contains("已开户");
        assertThat(directory.get(item.id())).isEqualTo(opened);
    }

    @Test void manuallyCorrectedCoverageIsNotOverwrittenByAnUnchangedSourceReplay() {
        var source=row("stable:source:2","测试甲","武汉光谷开发区",List.of());
        var imported=directory.importRows(List.of(source),false,7);
        var item=directory.get(imported.rows().get(0).recordId());
        var corrected=directory.update(item.id(),new FranchiseDirectoryService.RecordRequest("测试区域资料","测试甲","COVERAGE",source.originalRegionText(),List.of("420115"),"420000","420100",item.version()),7);
        for(boolean dryRun:List.of(true,false)) {
            var replay=directory.importRows(List.of(source),dryRun,7);
            assertThat(replay.skipped()).isEqualTo(1);assertThat(replay.unresolved()).isZero();
            assertThat(replay.rows().get(0).matchStatus()).isEqualTo("RESOLVED");
            assertThat(replay.rows().get(0).regionCodes()).containsExactly("420115");
            assertThat(replay.rows().get(0).name()).isEqualTo(corrected.name());
        }
        assertThat(directory.get(item.id())).isEqualTo(corrected);
    }

    @Test void coverageMayHaveOnlyARegionalDisplayNameButFranchiseNeedsARealName() {
        var item=directory.create(new FranchiseDirectoryService.RecordRequest("","测试甲","COVERAGE","黄陂区",List.of("420116"),"420000","420100",null),7);
        assertThat(item.name()).isEqualTo("测试甲 · 黄陂区");
        assertThat(item.recordType()).isEqualTo("COVERAGE");
        assertThatThrownBy(()->directory.create(new FranchiseDirectoryService.RecordRequest("","测试甲","FRANCHISE","黄陂区",List.of("420116"),"420000","420100",null),7))
                .isInstanceOf(ServiceException.class).hasMessageContaining("名称");
    }

    @Test void linkedEditsKeepAccountAndCountyInSyncWithoutLosingMultiCountyCoverage() {
        var item=create("黄陂区、新洲区",List.of("420116","420117"));
        var opened=directory.onboard(new FranchiseDirectoryService.FromDirectoryRequest(item.id(),item.version(),"测试老板","13900001238",null,null,"","420116"),7);
        var linked=directory.get(item.id());
        var renamed=directory.update(linked.id(),new FranchiseDirectoryService.RecordRequest("测试新档案名",linked.brand(),"FRANCHISE",linked.originalRegionText(),linked.regionCodes(),"420000","420100",linked.version()),7);
        assertThat(renamed.regionCodes()).containsExactly("420116","420117");
        var moved=directory.update(renamed.id(),new FranchiseDirectoryService.RecordRequest(renamed.name(),renamed.brand(),"FRANCHISE","新洲区",List.of("420117"),"420000","420100",renamed.version()),7);
        assertThat(moved.regionCodes()).containsExactly("420117");
        assertThat(jdbc.queryForObject("SELECT map_district_code FROM pf_franchise_account WHERE account_id=?",String.class,opened.accountId())).isEqualTo("420117");
        assertThat(jdbc.queryForObject("SELECT name FROM system_dept WHERE id=?",String.class,opened.orgId())).isEqualTo("测试新档案名");
        assertThatThrownBy(()->directory.update(moved.id(),new FranchiseDirectoryService.RecordRequest(moved.name(),moved.brand(),"COVERAGE","新洲区",List.of("420117"),"420000","420100",moved.version()),7))
                .isInstanceOf(ServiceException.class).hasMessageContaining("已开户");
    }

    @Test void fourKnownBrandColorsDoNotDependOnSequenceIds() {
        var names=List.of("别墅先生","墅达人","金宅子","合家美宅");
        var colors=names.stream().map(name->FranchiseMapService.brandColor(1,name)).toList();
        assertThat(colors).doesNotHaveDuplicates();
        for(String name:names)assertThat(FranchiseMapService.brandColor(9999,name)).isEqualTo(FranchiseMapService.brandColor(1,name));
        assertThat(FranchiseMapService.brandColor(12345,"其他品牌")).matches("#[0-9a-fA-F]{6}");
    }

    @Test void historicalAccountSyncUsesDistrictCenterAndChangingCountyDoesNotKeepTheOldCode() {
        var opened=onboard.onboardWithDistrict("测试甲","湖北省","武汉市","黄陂区","测试老板","13900001234",null,null,"",7,"420116");
        jdbc.update("UPDATE pf_store_location SET longitude=114.123456,latitude=30.123456 WHERE account_id=?",opened.accountId());
        var district=regions.all().stream().filter(r->r.code().equals("420116")).findFirst().orElseThrow();
        var point=points(maps.snapshot()).get(0);
        assertThat(point.longitude()).isEqualTo(district.longitude());assertThat(point.latitude()).isEqualTo(district.latitude());
        assertThat(point.locationPrecision()).isEqualTo("district");
        jdbc.update("UPDATE pf_franchise_account SET area='新洲区' WHERE account_id=?",opened.accountId());
        assertThat(directory.get(opened.accountId()).regionCodes()).containsExactly("420117");
        assertThat(directory.get(opened.accountId()).originalRegionText()).isEqualTo("新洲区");
        assertThat(jdbc.queryForObject("SELECT map_district_code FROM pf_franchise_account WHERE account_id=?",String.class,opened.accountId())).isNull();
        assertThat(points(maps.snapshot())).extracting(FranchiseMapService.Point::regionCode).containsExactly("420117");
    }

    @Test void updatesCheckVersionAndNeverTrustRegionCodesFromAnotherProvince() {
        var item=create("黄陂区",List.of("420116"));
        var updated=directory.update(item.id(),new FranchiseDirectoryService.RecordRequest("新名称","测试甲","COVERAGE","新洲区",List.of("420117"),"420000","420100",item.version()),7);
        assertThat(updated.regionCodes()).containsExactly("420117");
        assertThatThrownBy(()->directory.update(item.id(),new FranchiseDirectoryService.RecordRequest("旧编辑","测试甲","COVERAGE","黄陂区",List.of("420116"),"420000","420100",item.version()),7)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(()->create("北京东城区",List.of("110101"))).isInstanceOf(ServiceException.class).hasMessageContaining("省份");
        assertThat(directory.page("新名称",null,null,1,20).getTotal()).isEqualTo(1);
        assertThat(directory.page("%",null,null,1,20).getTotal()).isZero();
        assertThat(directory.page("",null,null,2,20).getList()).isEmpty();
        assertThatThrownBy(()->directory.page("",null,null,1,101)).isInstanceOf(ServiceException.class);
    }
}
