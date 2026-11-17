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
    @Test void failedValidationNeverAppearsAndRetryCompletesSameEntry() {
        long id=upload(alice,"图.png","image/png","PERSONAL");
        when(assets.completeUpload(any(AssetAccessPort.Actor.class),eq(id))).thenThrow(new IllegalArgumentException("暂时失败"))
                .thenReturn(new AssetAccessPort.AssetCompleted(id,"ACCEPTED",null));
        assertThatThrownBy(()->service.complete(alice,id)).isInstanceOf(RuntimeException.class);
        assertThat(list(alice,"PERSONAL","ALL","",1)).isEmpty();service.complete(alice,id);
        assertThat(list(alice,"PERSONAL","ALL","",1)).hasSize(1);
        verify(assets,times(1)).createUploadTicket(any(AssetAccessPort.Actor.class),anyString(),anyString(),anyLong(),anyString());
    }
}
