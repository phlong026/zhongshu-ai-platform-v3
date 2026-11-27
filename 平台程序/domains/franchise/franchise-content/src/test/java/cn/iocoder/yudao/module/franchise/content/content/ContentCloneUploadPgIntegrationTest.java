package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.module.franchise.content.ContentPermissions;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentPersonaDO;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.content.provider.*;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.franchise.content.controller.admin.ContentCloneController;
import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentCloneUploadPgIntegrationTest extends FranchisePgIntegrationTest {
    @Resource DataSource dataSource;
    @Resource PlatformTransactionManager transactionManager;
    JdbcTemplate jdbc;
    ContentPersonaService personas;
    final PostSnapshot post=new PostSnapshot(11L,22L,33L,"store","sales-lead",null,false,Set.of());
    final String sha="a".repeat(64);
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(dataSource);jdbc.execute("TRUNCATE fr_content_digital_clone,fr_content_hifly_upload");
        personas=mock(ContentPersonaService.class);
        when(personas.list(33)).thenReturn(List.of(new FrContentPersonaDO().setId(21L).setEnabled(true),new FrContentPersonaDO().setId(23L).setEnabled(false)));
        when(personas.requireWritable(eq(post),eq(21L))).thenReturn(new FrContentPersonaDO().setId(21L).setEnabled(true));
        when(personas.requireWritable(eq(post),eq(23L))).thenReturn(new FrContentPersonaDO().setId(23L).setEnabled(false));
    }
    ContentCloneUploadService service(ContentProviderTransport transport) {
        var config=new ContentProviderProperties();config.getHifly().setApiKey("server-only-key");
        return new ContentCloneUploadService(jdbc,personas,config,transport,null);
    }
    ContentProviderTransport.Scripted grants() {
        return new ContentProviderTransport.Scripted(ContentProviderTransport.json(200,"{\"upload_url\":\"https://upload.hifly.cc/sample.mp3?Signature=fixture\",\"content_type\":\"audio/mp3\",\"file_id\":\"provider-file\"}",0));
    }
    long issue(ContentCloneUploadService service) {return Long.parseLong((String)service.issue(post,21,"VOICE","audio/mpeg",100,sha).get("uploadId"));}
    @Test void grantExposesOnlyScopedUploadReceiptAndKeepsNoMediaBytes() {
        var transport=grants();var service=service(transport);
        var ticket=service.issue(post,21,"VOICE","audio/mpeg",100,sha);
        assertThat(ticket.keySet()).containsExactlyInAnyOrder("uploadId","uploadUrl","contentType","expiresAt");
        assertThat(ticket.toString()).doesNotContain("server-only-key","provider-file");assertThat(ticket.get("contentType")).isEqualTo("audio/mp3");
        assertThat(transport.calls).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT status FROM fr_content_hifly_upload",String.class)).isEqualTo("ISSUED");
    }
    @Test void completingIsIdempotentButCannotTrustOtherPeopleOrKindsOrUnfinishedUploads() {
        var service=service(grants());long id=issue(service);
        assertThatThrownBy(()->service.requireUploaded(post,21,id,"VOICE")).hasMessageContaining("尚未上传完成");
        assertThat(service.complete(post,id)).isEqualTo(service.complete(post,id));
        assertThat(service.requireUploaded(post,21,id,"VOICE").fileId()).isEqualTo("provider-file");
        assertThatThrownBy(()->service.requireUploaded(post,22,id,"VOICE")).hasMessageContaining("当前人物");
        assertThatThrownBy(()->service.requireUploaded(post,21,id,"AVATAR")).hasMessageContaining("MP4");
        for(var other:List.of(new PostSnapshot(12L,99L,33L,"store","boss",null,false,Set.of()),new PostSnapshot(12L,22L,34L,"store","boss",null,false,Set.of())))
            assertThatThrownBy(()->service.complete(other,id)).hasMessageContaining("无权使用");
    }
    @Test void concurrentConfirmationsShareTheClientReceipt() throws Exception {
        var service=service(grants());
        long id=issue(service);
        var start=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->{start.await();return service.complete(post,id);});
            var second=pool.submit(()->{start.await();return service.complete(post,id);});
            start.countDown();
            assertThat(first.get(5,java.util.concurrent.TimeUnit.SECONDS))
                .isEqualTo(second.get(5,java.util.concurrent.TimeUnit.SECONDS))
                .containsEntry("providerStatus","UNVERIFIED");
        } finally {
            pool.shutdownNow();
        }
    }
    @Test void invalidMediaAndInactivePersonNeverRequestVendorCredentials() {
        var transport=new ContentProviderTransport.Scripted();var service=service(transport);
        for(long size:new long[]{0,20L*1024*1024+1})assertThatThrownBy(()->service.issue(post,21,"VOICE","audio/mpeg",size,sha)).hasMessageContaining("20MB");
        assertThatThrownBy(()->service.issue(post,21,"AVATAR","audio/mpeg",100,sha)).hasMessageContaining("MP4");
        assertThatThrownBy(()->service.issue(post,23,"VOICE","audio/mpeg",100,sha)).hasMessageContaining("停用");
        assertThatThrownBy(()->service.issue(post,21,"VOICE","audio/mpeg",100,"bad-hash")).hasMessageContaining("校验");
        assertThat(transport.calls).isEmpty();
    }
    @Test void expiredUnfinishedGrantCannotBeCompleted() {
        var service=service(grants());long id=issue(service);
        jdbc.update("UPDATE fr_content_hifly_upload SET expires_at=now()-interval '1 second'");
        assertThatThrownBy(()->service.complete(post,id)).hasMessageContaining("过期");
        assertThat(jdbc.queryForObject("SELECT status FROM fr_content_hifly_upload",String.class)).isEqualTo("ISSUED");
    }
    @Test void browserReceiptsRequireTheOriginalPostAndFreezeTheExactFile() throws Exception {
        var service=service(grants());
        long id=Long.parseLong((String)service.prepareFileUpload(post,21,"VOICE","audio/mpeg").get("uploadId"));
        assertThatThrownBy(()->service.complete(post,id)).hasMessageContaining("过期");
        var changedPost=new PostSnapshot(12L,22L,33L,"store","sales",null,false,Set.of());
        assertThatThrownBy(()->service.completeFileUpload(changedPost,id,100,sha)).hasMessageContaining("岗位");
        assertThatThrownBy(()->service.completeFileUpload(post,id,21L<<20,sha));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->service.completeFileUpload(post,id,100,sha));
            var b=pool.submit(()->service.completeFileUpload(post,id,100,sha));
            assertThat(a.get()).isEqualTo(b.get()).containsEntry("providerStatus","UNVERIFIED");
        } finally { pool.shutdownNow(); }
        assertThatThrownBy(()->service.completeFileUpload(post,id,101,sha)).hasMessageContaining("变化");
        assertThat(service.requireUploaded(post,21,id,"VOICE").sha256()).isEqualTo(sha);
    }

    AssetAccessPort mediaAssets(String mime, double duration, int width, int height) {
        var assets = mock(AssetAccessPort.class);
        when(assets.contentMetadata(any(), eq(700L))).thenReturn(new AssetAccessPort.ContentMetadata(mime,sha,100));
        when(assets.readContentMedia(any(), eq(700L))).thenReturn(new AssetAccessPort.ContentMedia(new byte[100],mime,sha,duration,width,height));
        return assets;
    }
    @Test void normalizedImportCanBePreviewedAndOnlyTransfersAfterConsent() {
        var transport = new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200,"{\"upload_url\":\"https://upload.hifly.cc/sample.mp3?Signature=fixture\",\"content_type\":\"audio/mp3\",\"file_id\":\"normalized-provider-file\"}",0),
                ContentProviderTransport.json(200,"",0));
        var service = service(transport); var assets = mediaAssets("audio/mpeg",6,0,0); service.setAssets(assets,org -> 88L);
        var first = service.importAsset(post,21,"VOICE",700);
        assertThat(first).containsEntry("status","UPLOADED").containsEntry("providerStatus","NOT_SENT")
                .containsEntry("assetId","700").containsEntry("mimeType","audio/mpeg");
        assertThat(service.importAsset(post,21,"VOICE",700)).isEqualTo(first);
        long id = Long.parseLong(first.get("uploadId").toString());
        assertThatThrownBy(() -> service.requireUploaded(post,21,id,"VOICE")).hasMessageContaining("尚未上传完成");
        for (String consent : Arrays.asList(null, "", "outdated-version")) {
            assertThatThrownBy(() -> service.requireAuthorizedUpload(post,21,id,"VOICE",consent)).hasMessageContaining("授权");
        }
        assertThat(transport.calls).isEmpty();
        assertThat(service.requireAuthorizedUpload(post,21,id,"VOICE",ContentCloneService.CONSENT_VERSION).fileId())
                .isEqualTo("normalized-provider-file");
        assertThat(service.requireAuthorizedUpload(post,21,id,"VOICE",ContentCloneService.CONSENT_VERSION).fileId())
                .isEqualTo("normalized-provider-file");
        assertThat(transport.calls).hasSize(2);
        assertThat(transport.calls.get(1).method()).isEqualTo("PUT");
        assertThat(transport.calls.get(1).body()).hasSize(100);
        assertThat(transport.calls.get(1).headers()).containsOnly(entry("Content-Type","audio/mp3"));
        verify(assets,times(4)).readContentMedia(new AssetAccessPort.Actor(33,88,22,11),700L);
        assertThatThrownBy(() -> service.complete(post,id)).hasMessageContaining("原素材");
        assertThatThrownBy(() -> service.completeFileUpload(post,id,100,sha)).hasMessageContaining("原素材");
        var otherPost = new PostSnapshot(12L,22L,33L,"store","sales",null,false,Set.of());
        assertThatThrownBy(() -> service.requireUploaded(otherPost,21,id,"VOICE")).hasMessageContaining("岗位");
        when(assets.readContentMedia(any(),eq(700L))).thenThrow(new IllegalArgumentException("source access revoked"));
        assertThatThrownBy(() -> service.importAsset(post,21,"VOICE",700)).hasMessageContaining("source access revoked");
        assertThat(transport.calls).hasSize(2);
    }
    @Test void browserAndLegacyDirectRoutesRequireConsentBeforeIssuingAnyGrant() {
        var transport = new ContentProviderTransport.Scripted();
        var current = mock(CurrentPost.class);
        when(current.require()).thenReturn(post);
        var controller = new ContentCloneController(
                mock(ContentCloneService.class), current, service(transport));
        assertThatThrownBy(() -> controller.upload(21, new ContentCloneController.Upload(
                "VOICE","audio/mpeg",100,sha,null))).hasMessageContaining("授权");
        assertThatThrownBy(() -> controller.fileTicket(21, new ContentCloneController.FileFormat(
                "VOICE","audio/mpeg",null))).hasMessageContaining("授权");
        assertThat(transport.calls).isEmpty();
    }

    @Test void actualCloneCreationOnlySendsTheSelectedAssetAfterAuthorization() {
        var transport = new ContentProviderTransport.Scripted(
                ContentProviderTransport.json(200,"{\"upload_url\":\"https://upload.hifly.cc/sample.mp3\",\"content_type\":\"audio/mp3\",\"file_id\":\"consented-file\"}",0),
                ContentProviderTransport.json(200,"",0),
                ContentProviderTransport.json(200,"{\"code\":0,\"task_id\":\"consented-task\"}",0));
        var uploads = service(transport);
        var assets=mediaAssets("audio/mpeg",6,0,0); uploads.setAssets(assets,org -> 88L);
        // The abandoned selection is never sent to the provider.
        when(assets.contentMetadata(any(),eq(701L))).thenReturn(new AssetAccessPort.ContentMetadata("audio/mpeg",sha,100));
        var selected=new byte[100]; Arrays.fill(selected,(byte)42);
        when(assets.readContentMedia(any(),eq(701L))).thenReturn(new AssetAccessPort.ContentMedia(selected,"audio/mpeg",sha,6,0,0));
        uploads.importAsset(post,21,"VOICE",700);
        long chosen=Long.parseLong(uploads.importAsset(post,21,"VOICE",701).get("uploadId").toString());
        var points=mock(OrgPointPort.class);
        var config=new ContentProviderProperties(); config.getHifly().setApiKey("server-only-key");
        var directory = mock(SystemDirectory.class);
        when(directory.roleHasPermission(post.roleCode(), ContentPermissions.PERSONA)).thenReturn(true);
        var clones=new ContentCloneService(jdbc,personas,uploads,points,config,transport,null,transactionManager,directory);
        assertThatThrownBy(()->clones.create(post,21,"VOICE","样本",chosen,null,"original-request",5))
                .hasMessageContaining("授权");
        assertThat(transport.calls).isEmpty();
        verifyNoInteractions(points);
        when(points.reserveConfirmed(any(),eq(5L))).thenReturn(100L);
        var created=clones.create(post,21,"VOICE","样本",chosen,ContentCloneService.CONSENT_VERSION,"original-request",5);
        assertThat(created).containsEntry("status","RUNNING");
        assertThat(clones.create(post,21,"VOICE","样本",chosen,ContentCloneService.CONSENT_VERSION,"original-request",5))
                .isEqualTo(created);
        assertThat(transport.calls).hasSize(3);
        assertThat(transport.calls.get(1).body()).isEqualTo(selected);
        verify(points).reserveConfirmed(any(),eq(5L));
    }
    @Test void invalidKindsDurationsAndSourceIntegrityCannotReachTheProvider() {
        var transport = new ContentProviderTransport.Scripted(); var service = service(transport);
        var assets = mediaAssets("audio/mpeg",4,0,0); service.setAssets(assets,org -> 88L);
        assertThatThrownBy(() -> service.importAsset(post,21,"VOICE",700)).hasMessageContaining("5秒");
        assertThatThrownBy(() -> service.importAsset(post,21,"AVATAR",700)).hasMessageContaining("人物视频");
        when(assets.readContentMedia(any(),eq(700L))).thenReturn(new AssetAccessPort.ContentMedia(new byte[100],"audio/mpeg","b".repeat(64),6,0,0));
        assertThatThrownBy(() -> service.importAsset(post,21,"VOICE",700)).hasMessageContaining("摘要不一致");
        when(assets.contentMetadata(any(),eq(700L))).thenThrow(new IllegalStateException("revoked"));
        assertThatThrownBy(() -> service.importAsset(post,21,"VOICE",700)).hasMessageContaining("revoked");
        assertThat(transport.calls).isEmpty();
    }
    @Test void concurrentAuthorizedSubmissionsCannotDuplicateTheProviderTransfer() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var count = new java.util.concurrent.atomic.AtomicInteger();
        ContentProviderTransport transport = (method,url,headers,bytes,timeout) -> {
            count.incrementAndGet();
            if ("PUT".equals(method)) { started.countDown(); release.await(5,java.util.concurrent.TimeUnit.SECONDS); return ContentProviderTransport.json(200,"",0); }
            return ContentProviderTransport.json(200,"{\"upload_url\":\"https://upload.hifly.cc/sample.mp3\",\"content_type\":\"audio/mp3\",\"file_id\":\"concurrent-provider-file\"}",0);
        };
        var service = service(transport); service.setAssets(mediaAssets("audio/mpeg",6,0,0),org -> 88L);
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            long id=Long.parseLong(service.importAsset(post,21,"VOICE",700).get("uploadId").toString());
            var first = pool.submit(() -> service.requireAuthorizedUpload(post,21,id,"VOICE",ContentCloneService.CONSENT_VERSION));
            assertThat(started.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.requireAuthorizedUpload(post,21,id,"VOICE",ContentCloneService.CONSENT_VERSION))
                    .isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class,e -> assertThat(e.getCode()).isEqualTo(1_114_000_012));
            release.countDown();
            assertThat(first.get(5,java.util.concurrent.TimeUnit.SECONDS).fileId()).isEqualTo("concurrent-provider-file");
            assertThat(count.get()).isEqualTo(2);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void concurrentImportsHoldABoundedNumberOfMediaBuffersAndReleaseTheirSlots() throws Exception {
        when(personas.requireWritable(eq(post),eq(24L))).thenReturn(new FrContentPersonaDO().setId(24L).setEnabled(true));
        var started=new java.util.concurrent.CountDownLatch(2); var release=new java.util.concurrent.CountDownLatch(1);
        var grants=new java.util.concurrent.atomic.AtomicInteger();
        ContentProviderTransport transport=(method,url,headers,bytes,timeout)->{
            if("PUT".equals(method)){started.countDown();release.await(8,java.util.concurrent.TimeUnit.SECONDS);return ContentProviderTransport.json(200,"",0);}
            return ContentProviderTransport.json(200,"{\"upload_url\":\"https://upload.hifly.cc/sample.mp3\",\"content_type\":\"audio/mp3\",\"file_id\":\"bounded-"+grants.incrementAndGet()+"\"}",0);
        };
        var service=service(transport); var assets=mediaAssets("audio/mpeg",6,0,0);service.setAssets(assets,org->88L);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            long one=Long.parseLong(service.importAsset(post,21,"VOICE",700).get("uploadId").toString());
            long two=Long.parseLong(service.importAsset(post,24,"VOICE",700).get("uploadId").toString());
            var first=pool.submit(()->service.requireAuthorizedUpload(post,21,one,"VOICE",ContentCloneService.CONSENT_VERSION));
            var second=pool.submit(()->service.requireAuthorizedUpload(post,24,two,"VOICE",ContentCloneService.CONSENT_VERSION));
            assertThat(started.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->service.importAsset(post,21,"VOICE",701))
                .isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class,e->assertThat(e.getCode()).isEqualTo(1_114_000_012));
            verify(assets,never()).readContentMedia(any(),eq(701L));
            release.countDown();
            assertThat(first.get(5,java.util.concurrent.TimeUnit.SECONDS).fileId()).startsWith("bounded-");
            assertThat(second.get(5,java.util.concurrent.TimeUnit.SECONDS).fileId()).startsWith("bounded-");
            assertThat(service.importAsset(post,21,"VOICE",700)).containsEntry("status","UPLOADED");
            assertThat(grants.get()).isEqualTo(2);
        }finally{release.countDown();pool.shutdownNow();}
    }

    String undo() throws Exception {
        try(var input=getClass().getResourceAsStream("/db/migration-undo/franchise/V20261116.924__undo_fr_content_hifly_direct_upload.sql")){return new String(Objects.requireNonNull(input).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
    }
    @Test void emptySchemaCanRollBackInsideTransaction() throws Exception {
        String sql=undo();new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            jdbc.execute(sql);assertThat(jdbc.queryForObject("SELECT to_regclass('public.fr_content_hifly_upload')::text",String.class)).isNull();status.setRollbackOnly();
        });
    }
    @Test void rollbackCannotEraseEvenUncompletedVendorUploadFacts() throws Exception {
        issue(service(grants()));String sql=undo();
        assertThatThrownBy(()->jdbc.execute(sql)).rootCause().hasMessageContaining("禁止回滚924");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_content_hifly_upload",Long.class)).isEqualTo(1);
    }
}
