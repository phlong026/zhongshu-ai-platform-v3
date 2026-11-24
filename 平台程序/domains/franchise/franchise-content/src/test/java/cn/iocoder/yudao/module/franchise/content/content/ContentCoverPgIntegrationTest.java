package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.franchise.content.provider.*;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContentCoverPgIntegrationTest extends FranchisePgIntegrationTest {
    @Resource JdbcTemplate jdbc;
    private AssetAccessPort assets;
    private StoreServiceAccountPort accounts;
    private CoverImageFetcher images;
    private ContentProviderProperties properties;
    private final PostSnapshot post = new PostSnapshot(11, 22, 33, "store", "sales", null, false, Set.of());
    private final Map<String,Object> input = Map.of("templateId", "cs_123", "title", "乡墅案例", "canvasPreset", "9:16");

    @BeforeEach void setupCovers() {
        TenantContextHolder.setTenantId(1L);
        assets = mock(AssetAccessPort.class); accounts = mock(StoreServiceAccountPort.class); images = mock(CoverImageFetcher.class);
        when(accounts.requireServiceAccount(anyLong())).thenReturn(777L);
        when(images.fetch(anyString())).thenReturn(new CoverImageFetcher.Image(new byte[]{1,2,3}, "image/jpeg"));
        when(assets.importContentImage(any(),any(),eq("image/jpeg"))).thenReturn(888L);
        when(assets.contentMetadata(any(),eq(888L))).thenReturn(new AssetAccessPort.ContentMetadata("image/jpeg","hash",3));
        properties = new ContentProviderProperties(); properties.setProvider(ContentProviderProperties.ProviderMode.REAL);
        properties.getHifly().setApiKey("fixture-only");
    }
    private ContentCoverService service(ContentProviderTransport transport) {
        return new ContentCoverService(jdbc,assets,accounts,properties,transport,null,images);
    }
    private ContentProviderTransport.Scripted success() {
        return new ContentProviderTransport.Scripted(ContentProviderTransport.json(200,
                "{\"code\":0,\"cover_id\":\"cover_123\",\"url\":\"https://image.example/cover.jpg?sig=fixture\"}",0));
    }

    @Test void lostResponseCanBeRecoveredAfterServiceRestartWithoutGeneratingAgain() {
        var transport = success(); var key = UUID.randomUUID().toString();
        var first = service(transport).create(post,key,input);
        assertThat(first).containsEntry("status","READY").containsEntry("terminal",true).containsEntry("coverAssetId","888");
        assertThat(service(transport).create(post,key,input)).isEqualTo(first);
        assertThat(transport.calls).hasSize(1);
        verify(assets,times(1)).importContentImage(eq(new AssetAccessPort.Actor(33,777,22,11)),any(),eq("image/jpeg"));
        assertThatThrownBy(() -> service(transport).create(post,key,Map.of("templateId","cs_123","title","已修改","canvasPreset","9:16")))
                .hasMessageContaining("内容已变化");
        assertThat(transport.calls).hasSize(1);
    }

    @Test void uncertainSubmissionNeverResubmitsAndDefinitiveFailureIsTerminal() {
        var transport = new ContentProviderTransport.Scripted(true); var key = UUID.randomUUID().toString();
        assertThat(service(transport).create(post,key,input)).containsEntry("status","UNCERTAIN").containsEntry("terminal",false);
        assertThat(service(transport).create(post,key,input)).containsEntry("status","UNCERTAIN");
        assertThat(transport.calls).hasSize(1); verifyNoInteractions(images);
        var rejected = new ContentProviderTransport.Scripted(ContentProviderTransport.json(200,"{\"code\":1013}",0));
        var rejectedKey = UUID.randomUUID().toString();
        assertThat(service(rejected).create(post,rejectedKey,input)).containsEntry("status","FAILED").containsEntry("terminal",true);
        assertThat(service(rejected).create(post,rejectedKey,input)).containsEntry("status","FAILED");
        assertThat(rejected.calls).hasSize(1);
    }

    @Test void failedArchiveRetriesOnlyDownloadAndStorageNotGeneration() {
        var transport = success(); var key = UUID.randomUUID().toString();
        when(images.fetch(anyString())).thenThrow(new ContentProviderException.Unavailable("archive busy"))
                .thenReturn(new CoverImageFetcher.Image(new byte[]{1,2,3},"image/jpeg"));
        assertThat(service(transport).create(post,key,input)).containsEntry("status","ARCHIVE_PENDING").containsEntry("terminal",false);
        assertThat(service(transport).create(post,key,input)).containsEntry("status","READY").containsEntry("coverAssetId","888");
        assertThat(transport.calls).hasSize(1); verify(images,times(2)).fetch(anyString());
        verify(assets,times(1)).importContentImage(any(),any(),anyString());
    }

    @Test void definiteImageRejectionTerminatesWithoutRepeatedImportsOrProviderCalls() {
        var transport = success(); var key = UUID.randomUUID().toString();
        when(assets.importContentImage(any(),any(),anyString()))
                .thenThrow(new AssetAccessPort.ContentRejectedException("invalid pixels"));
        assertThat(service(transport).create(post,key,input)).containsEntry("status","FAILED").containsEntry("terminal",true);
        assertThat(service(transport).create(post,key,input)).containsEntry("status","FAILED");
        assertThat(transport.calls).hasSize(1);
        verify(images,times(1)).fetch(anyString());
        verify(assets,times(1)).importContentImage(any(),any(),anyString());
    }

    @Test void concurrentReplaySeesCommittedIntentAndDoesNotMakeASecondProviderCall() throws Exception {
        var key = UUID.randomUUID().toString();
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        ContentProviderTransport transport = (method,url,headers,body,timeout) -> {
            calls.incrementAndGet(); entered.countDown();
            if (!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("fixture timeout");
            return ContentProviderTransport.json(200,"{\"code\":0,\"cover_id\":\"c\",\"url\":\"https://image.example/c.jpg\"}",0);
        };
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> {
                TenantContextHolder.setTenantId(1L);
                try { return service(transport).create(post,key,input); }
                finally { TenantContextHolder.clear(); }
            });
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(service(transport).create(post,key,input)).containsEntry("status","PROCESSING").containsEntry("terminal",false);
            release.countDown();
            assertThat(first.get(10,java.util.concurrent.TimeUnit.SECONDS)).containsEntry("status","READY");
            assertThat(calls.get()).isEqualTo(1);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void tenantUserOrganizationAndPostAreAllPartOfTheIdempotencyScope() {
        var key = UUID.randomUUID().toString();
        String first = (String)service(success()).create(post,key,input).get("requestId");
        for (PostSnapshot other : java.util.List.of(
                new PostSnapshot(12,22,33,"store","sales",null,false,Set.of()),
                new PostSnapshot(11,23,33,"store","sales",null,false,Set.of()),
                new PostSnapshot(11,22,34,"store","sales",null,false,Set.of()))) {
            assertThat(service(success()).create(other,key,input).get("requestId")).isNotEqualTo(first);
        }
        TenantContextHolder.setTenantId(2L);
        assertThat(service(success()).create(post,key,input).get("requestId")).isNotEqualTo(first);
    }
}
