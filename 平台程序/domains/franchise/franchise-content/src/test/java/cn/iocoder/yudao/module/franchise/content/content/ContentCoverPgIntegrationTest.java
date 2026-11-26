package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.franchise.content.controller.admin.ContentCoverController;
import cn.iocoder.yudao.module.franchise.content.provider.ContentProviderProperties;
import cn.iocoder.yudao.module.franchise.content.provider.CoverImageFetcher;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.COVER_GENERATION_RETIRED;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContentCoverPgIntegrationTest extends FranchisePgIntegrationTest {
    private static final String IMAGE_URL = "https://image.example/archived-cover.jpg";
    private static final Map<String,Object> INPUT = Map.of("templateId","cs_123","title","乡墅案例","canvasPreset","9:16");
    // Historical digest from the original generator, including its UTF-8 JSON field order.
    private static final String ORIGINAL_HASH = "ca54b8d2a31e78367a576cf40b01b2834ec89f182fab3e9052c2752ed8fa2df8";
    private final PostSnapshot post = new PostSnapshot(11,22,33,"store","sales",null,false,Set.of());
    private final String key = UUID.randomUUID().toString();
    @Resource JdbcTemplate jdbc;
    private AssetAccessPort assets;
    private CoverImageFetcher images;
    private ContentCoverService service;

    @BeforeEach void setupCovers() {
        TenantContextHolder.setTenantId(1L);
        currentPost.use(post);
        assets=mock(AssetAccessPort.class);
        images=mock(CoverImageFetcher.class);
        var accounts=mock(StoreServiceAccountPort.class);
        when(accounts.requireServiceAccount(post.orgId())).thenReturn(777L);
        var properties=new ContentProviderProperties();
        properties.setProvider(ContentProviderProperties.ProviderMode.REAL);
        properties.getHifly().setApiKey("fixture-only");
        service=new ContentCoverService(jdbc,assets,accounts,properties,images);
    }

    @AfterEach void cleanupCovers() { currentPost.clear(); TenantContextHolder.clear(); }

    private long original(String state) {
        long id=IdWorker.getId();
        jdbc.update("""
                INSERT INTO fr_content_cover_request(id,tenant_id,company_id,author_user_id,author_post_id,
                    idempotency_key,request_hash,template_id,title,canvas_preset,status,provider_cover_id,provider_url,cover_asset_id)
                VALUES(?,1,33,22,11,?,?,'cs_123','乡墅案例','9:16',?,?,?,?)
                """,id,key,ORIGINAL_HASH,state,"ARCHIVE_PENDING".equals(state)?"existing-receipt":null,
                "ARCHIVE_PENDING".equals(state)?IMAGE_URL:null,"READY".equals(state)?888L:null);
        return id;
    }

    private void retired(Executable call) {
        assertThat(assertThrows(ServiceException.class,call).getCode()).isEqualTo(COVER_GENERATION_RETIRED.getCode());
    }

    @Test void oldTemplateAndNewCreateEndpointsRetireWithoutCreatingARequestOrReachingFiles() {
        var api=new ContentCoverController(currentPost,service);
        retired(api::templates);
        retired(()->api.recover(key,INPUT));
        retired(()->api.recover(key,INPUT));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_cover_request WHERE idempotency_key=?",Long.class,key)).isZero();
        verifyNoInteractions(images,assets);
    }

    @Test void oldReadyRequestCanOnlyRecoverTheSameScopedImageAndSamePayload() {
        long id=original("READY");
        var ready=service.recover(post,key,INPUT);
        assertThat(ready).containsEntry("requestId",Long.toString(id)).containsEntry("coverAssetId","888").containsEntry("status","READY");
        assertThat(ready).doesNotContainKeys("providerUrl","url");
        assertThatThrownBy(()->service.recover(post,key,Map.of("templateId","cs_123","title","变化","canvasPreset","9:16")))
                .isInstanceOf(ServiceException.class).hasMessageContaining("内容已变化");
        for (var other:List.of(new PostSnapshot(12,22,33,"store","sales",null,false,Set.of()),
                new PostSnapshot(11,23,33,"store","sales",null,false,Set.of()),
                new PostSnapshot(11,22,34,"store","sales",null,false,Set.of()))) {
            retired(()->service.recover(other,key,INPUT));
        }
        TenantContextHolder.setTenantId(2L);
        retired(()->service.recover(post,key,INPUT));
        verify(assets).contentMetadata(new AssetAccessPort.Actor(33,777,22,11),888L);
        verifyNoMoreInteractions(assets);
        verifyNoInteractions(images);
    }

    @Test void oldUncertainAndFailedRequestsNeverCreateAnotherAttempt() {
        long id=original("PROCESSING");
        assertThat(service.recover(post,key,INPUT)).containsEntry("status","PROCESSING");
        jdbc.update("UPDATE fr_content_cover_request SET create_time=now()-interval '1 day' WHERE id=?",id);
        assertThat(service.recover(post,key,INPUT)).containsEntry("status","UNCERTAIN");
        assertThat(jdbc.queryForObject("SELECT status FROM fr_content_cover_request WHERE id=?",String.class,id)).isEqualTo("PROCESSING");
        jdbc.update("UPDATE fr_content_cover_request SET status='UNCERTAIN' WHERE id=?",id);
        assertThat(service.recover(post,key,INPUT)).containsEntry("requestId",Long.toString(id)).containsEntry("status","UNCERTAIN");
        jdbc.update("UPDATE fr_content_cover_request SET status='FAILED',failure_reason='原单失败' WHERE id=?",id);
        assertThat(service.recover(post,key,INPUT)).containsEntry("status","FAILED").containsEntry("message","原单失败");
        verifyNoInteractions(images,assets);
    }

    @Test void onlyOneConcurrentReplayCanArchiveAnAlreadyIssuedImageReceipt() throws Exception {
        original("ARCHIVE_PENDING");
        var entered=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        when(images.fetch(IMAGE_URL)).thenAnswer(invocation->{
            entered.countDown();
            assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();
            return new CoverImageFetcher.Image(new byte[]{1,2,3},"image/jpeg");
        });
        when(assets.importContentImage(any(),any(),eq("image/jpeg"))).thenReturn(888L);
        var executor=Executors.newSingleThreadExecutor();
        try {
            var first=executor.submit(()->{
                TenantContextHolder.setTenantId(1L);
                try { return service.recover(post,key,INPUT); }
                finally { TenantContextHolder.clear(); }
            });
            assertThat(entered.await(8,TimeUnit.SECONDS)).isTrue();
            assertThat(service.recover(post,key,INPUT)).containsEntry("status","ARCHIVE_PENDING");
            release.countDown();
            var archived=first.get(10,TimeUnit.SECONDS);
            assertThat(archived).containsEntry("status","READY").containsEntry("coverAssetId","888");
            assertThat(service.recover(post,key,INPUT)).isEqualTo(archived);
            verify(images).fetch(IMAGE_URL);
            verify(assets).importContentImage(any(),any(),eq("image/jpeg"));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void interruptedArchiveCanResumeTheSameReceiptAndRejectedPixelsAreTerminal() {
        long id=original("ARCHIVE_PENDING");
        when(images.fetch(IMAGE_URL)).thenThrow(new IllegalStateException("offline"))
                .thenReturn(new CoverImageFetcher.Image(new byte[]{9},"image/jpeg"));
        when(assets.importContentImage(any(),any(),anyString())).thenThrow(new AssetAccessPort.ContentRejectedException("invalid image"));
        assertThat(service.recover(post,key,INPUT)).containsEntry("status","ARCHIVE_PENDING");
        assertThat(service.recover(post,key,INPUT)).containsEntry("status","FAILED");
        assertThat(service.recover(post,key,INPUT)).containsEntry("status","FAILED");
        assertThat(jdbc.queryForObject("SELECT provider_cover_id FROM fr_content_cover_request WHERE id=?",String.class,id)).isEqualTo("existing-receipt");
        verify(images,times(2)).fetch(IMAGE_URL);
        verify(assets).importContentImage(any(),any(),anyString());
    }
}
