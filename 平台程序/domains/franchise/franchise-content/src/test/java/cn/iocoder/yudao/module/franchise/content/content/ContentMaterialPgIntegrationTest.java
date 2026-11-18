package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.franchise.api.*;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.iocoder.yudao.module.franchise.content.dal.*;
import cn.iocoder.yudao.module.franchise.content.provider.*;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import org.springframework.test.util.ReflectionTestUtils;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;

@Import({ContentMaterialService.class, ContentMaterialVisibility.class})
class ContentMaterialPgIntegrationTest extends FranchisePgIntegrationTest {
    @Resource ContentMaterialService service;
    @Resource ContentMaterialVisibility visibility;
    @Resource JdbcTemplate jdbc;
    @MockitoBean AssetAccessPort assets;
    @MockitoBean StoreServiceAccountPort accounts;
    static final AtomicLong IDS=new AtomicLong(1000000);
    final PostSnapshot alice=new PostSnapshot(1,11,101,"store","sales",null,false,Set.of());
    final PostSnapshot bob=new PostSnapshot(2,22,101,"store","sales",null,false,Set.of());
    @BeforeEach void setup() {
        TenantContextHolder.setTenantId(1L);
        jdbc.update("DELETE FROM fr_content_material WHERE parent_asset_id IS NOT NULL");jdbc.update("DELETE FROM fr_content_material");
        when(accounts.requireServiceAccount(anyLong())).thenReturn(999L);
        when(assets.createUploadTicket(any(AssetAccessPort.Actor.class),eq("CONTENT_ASSET"),anyString(),anyLong(),anyString()))
                .thenAnswer(i->new AssetAccessPort.UploadTicketView(IDS.incrementAndGet(),"https://storage.example/upload"));
        when(assets.completeUpload(any(AssetAccessPort.Actor.class),anyLong())).thenAnswer(i->new AssetAccessPort.AssetCompleted(i.getArgument(1),"ACCEPTED",null));
        when(assets.createDownloadUrl(any(AssetAccessPort.Actor.class),anyLong())).thenReturn(new AssetAccessPort.DownloadUrlView("https://storage.example/content",100L));
    }
    @AfterEach void clearTenant() {TenantContextHolder.clear();}
    Map<String,Object> body(String name,String mime,String scope) {return new HashMap<>(Map.of("name",name,"mimeType",mime,"visibility",scope,"sizeBytes",1024,"sha256","a".repeat(64)));}
    long upload(PostSnapshot p,String name,String mime,String scope) {return Long.parseLong((String)service.upload(p,body(name,mime,scope)).get("assetId"));}
    List<?> list(PostSnapshot p,String scope,String type,String query,int page) {return (List<?>)service.page(p,scope,type,query,page).get("items");}
    @Test void actualUploadsStayPrivateUntilSharedAndCannotBeEditedByCoworkers() {
        long id=upload(alice,"院子.png","image/png","PERSONAL");
        assertThat(list(alice,"PERSONAL","ALL","",1)).isEmpty();
        service.complete(alice,id);service.complete(alice,id);
        assertThat(list(alice,"PERSONAL","IMAGE","院",1)).hasSize(1);
        assertThat(list(bob,"PERSONAL","ALL","",1)).isEmpty();assertThat(list(bob,"COMPANY","ALL","",1)).isEmpty();
        assertThatThrownBy(()->service.download(bob,id)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->service.complete(bob,id)).isInstanceOf(RuntimeException.class);
        service.update(alice,id,Map.of("visibility","COMPANY"));
        assertThat(list(bob,"COMPANY","IMAGE","",1)).hasSize(1);assertThat(service.download(bob,id)).containsKey("downloadUrl");
        assertThatThrownBy(()->service.update(bob,id,Map.of("name","stolen"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->service.remove(bob,id)).isInstanceOf(RuntimeException.class);
        var otherOrg=new PostSnapshot(3,11,102,"store","sales",null,false,Set.of());
        assertThatThrownBy(()->service.download(otherOrg,id)).isInstanceOf(RuntimeException.class);
        TenantContextHolder.setTenantId(2L);assertThatThrownBy(()->service.download(alice,id)).isInstanceOf(RuntimeException.class);
    }
    @Test void genericAssetVisibilityAlsoProtectsCoversAndKeepsPersistedTaskAccess() {
        long id=upload(alice,"口播.mp4","video/mp4","PERSONAL");service.complete(alice,id);
        var cover=body("封面","image/png","PERSONAL");cover.put("coverOf",Long.toString(id));
        long coverId=Long.parseLong((String)service.upload(alice,cover).get("assetId"));service.complete(alice,coverId);
        assertThat(list(alice,"PERSONAL","ALL","",1)).hasSize(1);
        var b=new AssetAccessPort.Actor(101,999,22,2);
        assertThatThrownBy(()->visibility.requireReadable(1,b,id)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->visibility.requireReadable(1,b,coverId)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->service.upload(bob,cover)).isInstanceOf(RuntimeException.class);
        service.update(alice,id,Map.of("visibility","COMPANY"));visibility.requireReadable(1,b,id);visibility.requireReadable(1,b,coverId);
        assertThat(service.download(bob,coverId)).containsKey("downloadUrl");
        service.update(alice,id,Map.of("visibility","PERSONAL"));
        assertThatThrownBy(()->service.download(bob,coverId)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->visibility.requireReadable(1,b,coverId)).isInstanceOf(RuntimeException.class);
        service.remove(alice,id);assertThat(list(alice,"PERSONAL","ALL","",1)).isEmpty();
        visibility.requireReadable(1,new AssetAccessPort.Actor(101,999,0,0),id);
        assertThatThrownBy(()->service.download(alice,coverId)).isInstanceOf(RuntimeException.class);
    }
    @Test void filtersEscapeWildcardsAndPageOnlyRealReadyRecords() {
        for(int i=0;i<23;i++){long id=upload(alice,i==0?"100%完成":"素材"+i,i%2==0?"image/jpeg":"audio/mpeg","COMPANY");service.complete(alice,id);}
        assertThat(list(alice,"PERSONAL","ALL","",1)).hasSize(20);assertThat(list(alice,"PERSONAL","ALL","",2)).hasSize(3);
        assertThat(list(alice,"PERSONAL","ALL","%",1)).hasSize(1);
        assertThat(list(alice,"PERSONAL","AUDIO","",1)).hasSize(11);
        assertThatThrownBy(()->service.page(alice,"PERSONAL","ALL","",-1)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->service.upload(alice,body("name","image/svg+xml","PERSONAL"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->service.download(alice,99999)).isInstanceOf(RuntimeException.class);
    }
    @Test void fullPageCoverLookupUsesTheSameNumberOfSqlStatementsAsOneItem() throws Exception {
        Map<String,String> expected=new HashMap<>();
        for (int i=0;i<20;i++) {
            long id=upload(alice,"视频-"+i,"video/mp4","PERSONAL");service.complete(alice,id);
            var body=body("封面-"+i,"image/png","PERSONAL");body.put("coverOf",Long.toString(id));
            long coverId=Long.parseLong((String)service.upload(alice,body).get("assetId"));service.complete(alice,coverId);
            expected.put(Long.toString(id),Long.toString(coverId));
        }
        var source=mock(javax.sql.DataSource.class);
        var realSource=jdbc.getDataSource();
        when(source.getConnection()).thenAnswer(call->realSource.getConnection());
        var counted=new ContentMaterialService(source,assets,accounts);
        var full=(List<?>)counted.page(alice,"PERSONAL","ALL","",1).get("items");
        assertThat(full).hasSize(20);
        for (Object row:full) {
            var item=(Map<?,?>)row;
            assertThat(item.get("coverId")).isEqualTo(expected.get(item.get("id")));
        }
        verify(source,times(2)).getConnection();
        clearInvocations(source);
        assertThat((List<?>)counted.page(alice,"PERSONAL","ALL","视频-19",1).get("items")).hasSize(1);
        verify(source,times(2)).getConnection();
    }

    @Test void failedValidationNeverAppearsAndRetryCompletesSameEntry() {
        long id=upload(alice,"图.png","image/png","PERSONAL");
        when(assets.completeUpload(any(AssetAccessPort.Actor.class),eq(id))).thenThrow(new IllegalArgumentException("暂时失败"))
                .thenReturn(new AssetAccessPort.AssetCompleted(id,"ACCEPTED",null));
        assertThatThrownBy(()->service.complete(alice,id)).isInstanceOf(RuntimeException.class);
        assertThat(list(alice,"PERSONAL","ALL","",1)).isEmpty();service.complete(alice,id);
        assertThat(list(alice,"PERSONAL","ALL","",1)).hasSize(1);
        verify(assets,times(1)).createUploadTicket(any(AssetAccessPort.Actor.class),anyString(),anyString(),anyLong(),anyString());
    }

    @Test void privateAssetsAndCoversAreRejectedBeforeQueueReserveCacheOrProvider() {
        long main=upload(alice,"私人视频","video/mp4","PERSONAL");service.complete(alice,main);
        var cover=body("私有封面","image/png","PERSONAL");cover.put("coverOf",Long.toString(main));
        long coverId=Long.parseLong((String)service.upload(alice,cover).get("assetId"));service.complete(alice,coverId);
        when(assets.contentMetadata(any(AssetAccessPort.Actor.class),anyLong())).thenAnswer(call->{
            visibility.requireReadable(1,call.getArgument(0),call.getArgument(1));return new AssetAccessPort.ContentMetadata("video/mp4","a".repeat(64),1024);
        });
        var active=new java.util.concurrent.atomic.AtomicReference<>(bob);
        var gate=new ContentTaskAssetService(()->Optional.of(active.get()),accounts,assets);
        var store=new ContentTaskStore(jdbc);long taskCount=jdbc.queryForObject("SELECT count(*) FROM fr_content_task WHERE company_id=101",Long.class);var points=mock(OrgPointPort.class);var video=mock(VideoGenerationPort.class);var asr=mock(AsrPort.class);
        var commands=mock(FranchiseCommands.class);
        var tasks=new ContentTaskService(store,null,null,asr,null,null,video,assets,commands,new ContentProviderProperties(),points);
        ReflectionTestUtils.setField(tasks,"taskAssets",gate);
        Map<String,Object> request=new HashMap<>(Map.of("kind","ai_video","description","乡墅","duration",5,"resolution","768P","ratio","16:9"));
        request.put("references",List.of(Map.of("role","first_frame","assetId",Long.toString(main))));
        request.put(ContentTaskAssetService.SNAPSHOT_KEY,Map.of("assets",List.of(Map.of("assetId",Long.toString(main)))));
        assertThatThrownBy(()->tasks.createFromPost(bob,request,"private-main")).isInstanceOf(RuntimeException.class);
        request.put("references",List.of(Map.of("role","first_frame","assetId",Long.toString(coverId))));
        assertThatThrownBy(()->tasks.createFromPost(bob,request,"private-cover")).isInstanceOf(RuntimeException.class);
        var taskMapper=mock(FrContentTaskMapper.class);var scriptJdbc=mock(JdbcTemplate.class);
        var scripts=new ContentScriptService(tasks,mock(FrContentScriptMapper.class),taskMapper,mock(ContentPersonaService.class),scriptJdbc,commands);
        assertThatThrownBy(()->scripts.extract(bob,main,null,5.0,"a".repeat(64),"private-asr")).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(points,video,asr,taskMapper,scriptJdbc);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_task WHERE company_id=101",Long.class)).isEqualTo(taskCount);
        verify(assets,never()).createTaskDownloadUrl(anyLong(),anyLong(),anyString());

        when(assets.createTaskDownloadUrl(anyLong(),anyLong(),eq("CONTENT_ASSET"))).thenReturn(new AssetAccessPort.DownloadUrlView("https://storage.example/verified",100L));
        active.set(alice);
        var own=tasks.createFromPost(alice,request,"author-video");
        assertThat(gate.download(store.byId(101,own.getId()),coverId)).isEqualTo("https://storage.example/verified");
        assertThatThrownBy(()->gate.download(own,main)).isInstanceOf(ContentProviderException.Rejection.class);
        service.update(alice,main,Map.of("visibility","COMPANY"));active.set(bob);
        var shared=tasks.createFromPost(bob,request,"shared-video");assertThat(gate.download(shared,coverId)).isNotBlank();
        var sharedAsr=scripts.extract(bob,main,null,5.0,"forged-client-sha","shared-asr");
        assertThat(sharedAsr.getInputSha256()).isEqualTo("a".repeat(64));
        assertThat(gate.download(sharedAsr,main)).isNotBlank();
        verify(points,times(3)).reserve(any());
        // Existing authorized tasks continue after sharing is withdrawn; new submissions cannot.
        service.update(alice,main,Map.of("visibility","PERSONAL"));assertThat(gate.download(sharedAsr,main)).isNotBlank();
        assertThatThrownBy(()->scripts.extract(bob,main,null,5.0,"a".repeat(64),"after-unshare")).isInstanceOf(RuntimeException.class);
        verify(points,times(3)).reserve(any());
        var forged=new FrContentTaskDO().setId(shared.getId()).setCompanyId(102L).setAuthorUserId(22L).setInputSnapshot(shared.getInputSnapshot());
        assertThatThrownBy(()->gate.download(forged,coverId)).isInstanceOf(ContentProviderException.Rejection.class);
        var legacy=new FrContentTaskDO().setId(9L).setCompanyId(101L).setAuthorUserId(22L).setInputSnapshot("{\"assetId\":"+main+"}");
        assertThatThrownBy(()->gate.download(legacy,main)).isInstanceOf(ContentProviderException.Rejection.class);
        tasks.submitOne(store.byId(101,own.getId()));tasks.submitOne(store.byId(101,shared.getId()));
        verify(video,times(2)).submit(any(),any(),any());verifyNoInteractions(asr);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_task WHERE company_id=101",Long.class)).isEqualTo(taskCount+3);
    }
}
